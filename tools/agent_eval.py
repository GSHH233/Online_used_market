#!/usr/bin/env python3
"""
Evaluate Knowledge Agent / publish-assist orchestration quality.

Example:
  python tools/agent_eval.py ^
    --base-url http://localhost:8080 ^
    --dataset eval/datasets/agent_eval_dataset.json ^
    --bearer-token <JWT>
"""

from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from eval_auth import add_auth_args, resolve_bearer_token


FORBIDDEN_QUANTIFIED_ADVICE_RE = re.compile(
    r"(\d+(?:\.\d+)?\s*[-－—~～至到]\s*\d+(?:\.\d+)?\s*(?:[%％折倍元块])?)|"
    r"(\d+(?:\.\d+)?\s*(?:[%％折倍元块]))"
)

PRICE_ADVICE_TERMS = [
    "报价",
    "价格",
    "定价",
    "估价",
    "下调",
    "扣减",
    "扣除",
    "压价",
    "让利",
    "折扣",
    "折让",
    "成交价",
    "市场价",
    "议价",
    "挂高",
    "降价",
]


@dataclass
class AgentCaseResult:
    case_id: str
    trace_ok: bool
    source_type_ok: bool
    keyword_hit_rate: float
    source_doc_hit: bool
    block_ok: bool
    clarification_ok: bool
    forbidden_keyword_ok: bool
    quantified_advice_ok: bool
    detail_assertions_ok: bool
    detail_failures: list[str]
    error: str
    latency_ms: float
    trace_steps: list[str]
    trace_statuses: list[str]
    returned_source_types: list[str]
    returned_doc_ids: list[int]
    expected_source_types: list[str]
    expected_doc_ids: list[int]
    can_publish: bool | None
    risk_score: int | None
    answer_text: str

    @property
    def ok(self) -> bool:
        return (
            self.trace_ok
            and self.source_type_ok
            and self.keyword_hit_rate >= 0.5
            and self.source_doc_hit
            and self.block_ok
            and self.clarification_ok
            and self.forbidden_keyword_ok
            and self.quantified_advice_ok
            and self.detail_assertions_ok
            and not self.error
        )


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def post_json(url: str, payload: dict[str, Any], timeout_sec: float, bearer_token: str) -> tuple[dict[str, Any], float]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            raw = resp.read().decode("utf-8")
    finally:
        latency_ms = (time.perf_counter() - start) * 1000
    return json.loads(raw), latency_ms


def as_str_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(x) for x in value if x is not None]


def as_int_list(value: Any) -> list[int]:
    if not isinstance(value, list):
        return []
    out: list[int] = []
    for item in value:
        if isinstance(item, int):
            out.append(item)
        elif isinstance(item, str) and item.isdigit():
            out.append(int(item))
    return out


def compact_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False)


def build_generated_text(data: dict[str, Any]) -> str:
    parts: list[str] = []
    optimize = data.get("optimizeResult") or {}
    if isinstance(optimize, dict):
        parts.append(compact_text(optimize.get("optimizedTitle")))
        parts.append(compact_text(optimize.get("optimizedDescription")))
        parts.extend(compact_text(x) for x in optimize.get("tags") or [])
        parts.extend(compact_text(x) for x in optimize.get("riskWarnings") or [])
    parts.extend(compact_text(x) for x in data.get("riskWarnings") or [])
    parts.append(compact_text(data.get("priceSuggestion")))
    parts.append("canPublish=" + str(data.get("canPublish")))
    for evidence in data.get("riskEvidence") or []:
        if isinstance(evidence, dict):
            parts.append(compact_text(evidence.get("message")))
            parts.append(compact_text(evidence.get("hitText")))
            parts.append(compact_text(evidence.get("ruleCode")))
    return "\n".join(x for x in parts if x)


def build_source_text(data: dict[str, Any]) -> str:
    parts: list[str] = []
    for source in data.get("sources") or []:
        if isinstance(source, dict):
            parts.append(compact_text(source.get("docTitle")))
            parts.append(compact_text(source.get("snippet")))
    return "\n".join(x for x in parts if x)


def keyword_hit_rate(text: str, keywords: list[str]) -> float:
    if not keywords:
        return 1.0
    lower = text.lower()
    hits = sum(1 for kw in keywords if kw.lower() in lower)
    return hits / len(keywords)


def trace_ok(trace: list[dict[str, Any]], expected_steps: list[str]) -> tuple[bool, list[str], list[str]]:
    steps: list[str] = []
    statuses: list[str] = []
    for item in trace:
        if not isinstance(item, dict):
            continue
        raw_step = str(item.get("step") or "")
        step = raw_step.split("#", 1)[0]
        status = str(item.get("status") or "")
        steps.append(step)
        statuses.append(status)

    position = 0
    for expected in expected_steps:
        found = False
        while position < len(steps):
            if steps[position] == expected:
                found = True
                position += 1
                break
            position += 1
        if not found:
            return False, steps, statuses

    expected_set = set(expected_steps)
    relevant_statuses = [
        statuses[idx]
        for idx, step in enumerate(steps)
        if step in expected_set and idx < len(statuses)
    ]
    status_ok = all(status.upper() == "SUCCESS" for status in relevant_statuses)
    return status_ok, steps, statuses


def extract_sources(data: dict[str, Any]) -> tuple[list[str], list[int]]:
    types: list[str] = []
    doc_ids: list[int] = []
    for source in data.get("sources") or []:
        if not isinstance(source, dict):
            continue
        source_type = source.get("sourceType")
        if isinstance(source_type, str) and source_type:
            types.append(source_type)
        doc_id = source.get("docId")
        if isinstance(doc_id, int):
            doc_ids.append(doc_id)
    return types, doc_ids


def trace_messages(trace: list[dict[str, Any]]) -> str:
    parts: list[str] = []
    for item in trace:
        if not isinstance(item, dict):
            continue
        parts.append(compact_text(item.get("step")))
        parts.append(compact_text(item.get("label")))
        parts.append(compact_text(item.get("message")))
    return "\n".join(x for x in parts if x)


def contains_all_source_types(returned: list[str], expected: list[str]) -> bool:
    returned_set = {x.upper() for x in returned}
    return all(x.upper() in returned_set for x in expected)


def block_ok(data: dict[str, Any], should_block: bool, answer_text: str) -> bool:
    if not should_block:
        return True
    can_publish = data.get("canPublish")
    if can_publish is False:
        return True
    lower = answer_text.lower()
    return any(term in lower for term in ["禁售", "不能发布", "不可发布", "不建议发布", "风险较高", "拦截"])


def clarification_ok(answer_text: str, should_ask: bool) -> bool:
    if not should_ask:
        return True
    lower = answer_text.lower()
    return any(term in lower for term in ["补充", "无法", "不能确定", "无法确定", "请提供", "商品名称", "型号"])


def forbidden_keyword_ok(answer_text: str, forbidden_keywords: list[str]) -> bool:
    lower = answer_text.lower()
    return not any(keyword.lower() in lower for keyword in forbidden_keywords)


def quantified_advice_ok(answer_text: str, forbid_quantified_advice: bool) -> bool:
    if not forbid_quantified_advice:
        return True
    lower = answer_text.lower()
    if "具体数值需以证据" in lower or "不能编造具体数值" in lower:
        return True
    for sentence in re.split(r"[。！？!?\n]", answer_text):
        if not sentence.strip():
            continue
        if not any(term in sentence for term in PRICE_ADVICE_TERMS):
            continue
        if FORBIDDEN_QUANTIFIED_ADVICE_RE.search(sentence):
            return False
    return True


def detailed_assertions(
    case: dict[str, Any],
    data: dict[str, Any],
    generated_text: str,
    eval_text: str,
    trace: list[dict[str, Any]],
    trace_steps: list[str],
    returned_source_types: list[str],
    returned_doc_ids: list[int],
    can_publish: bool | None,
    risk_score: int | None,
) -> tuple[bool, list[str]]:
    failures: list[str] = []
    lower_generated = generated_text.lower()
    lower_eval = eval_text.lower()
    trace_text = trace_messages(trace).lower()
    source_type_set = {x.upper() for x in returned_source_types}
    trace_step_set = set(trace_steps)

    for kw in as_str_list(case.get("expectedDecisionReasonKeywords")):
        if kw.lower() not in lower_eval:
            failures.append(f"missingDecisionKeyword:{kw}")

    any_keyword_groups = case.get("expectedDecisionReasonAnyKeywords")
    if isinstance(any_keyword_groups, list):
        for idx, group in enumerate(any_keyword_groups):
            candidates = as_str_list(group)
            if candidates and not any(kw.lower() in lower_eval for kw in candidates):
                failures.append(f"missingAnyDecisionKeywordGroup:{idx}:{'/'.join(candidates)}")

    for kw in as_str_list(case.get("mustNotUseMemoryFacts")):
        if kw.lower() in lower_generated:
            failures.append(f"usedMemoryFact:{kw}")

    fallback_rule_code = case.get("expectedFallbackRuleCode")
    if fallback_rule_code is not None and str(fallback_rule_code).lower() not in trace_text:
        failures.append(f"missingFallbackRuleCode:{fallback_rule_code}")

    if "expectedCanPublish" in case and can_publish is not case.get("expectedCanPublish"):
        failures.append(f"expectedCanPublish:{case.get('expectedCanPublish')},actual:{can_publish}")

    if "expectedRiskMin" in case and risk_score is not None and risk_score < int(case["expectedRiskMin"]):
        failures.append(f"riskBelowMin:{risk_score}<{case['expectedRiskMin']}")

    if "expectedRiskMax" in case and risk_score is not None and risk_score > int(case["expectedRiskMax"]):
        failures.append(f"riskAboveMax:{risk_score}>{case['expectedRiskMax']}")

    for doc_id in as_int_list(case.get("requiredSourceDocIds")):
        if doc_id not in returned_doc_ids:
            failures.append(f"missingRequiredDoc:{doc_id}")

    for source_type in as_str_list(case.get("forbidSourceTypes")):
        if source_type.upper() in source_type_set:
            failures.append(f"forbiddenSourceType:{source_type}")

    expected_by_step = case.get("expectedTraceSourceTypesByStep")
    if isinstance(expected_by_step, dict):
        for step, source_types in expected_by_step.items():
            if str(step) not in trace_step_set:
                failures.append(f"missingStepForSourceCheck:{step}")
                continue
            for source_type in as_str_list(source_types):
                if source_type.upper() not in source_type_set:
                    failures.append(f"missingSourceTypeForStep:{step}:{source_type}")

    must_ask = case.get("mustAskClarification")
    if must_ask is not None:
        ask_ok = clarification_ok(eval_text, bool(must_ask))
        if not ask_ok:
            failures.append("clarificationDetailFailed")

    return not failures, failures


def evaluate_case(base_url: str, case: dict[str, Any], timeout_sec: float, bearer_token: str) -> AgentCaseResult:
    case_id = str(case.get("caseId", "UNKNOWN"))
    expected_steps = as_str_list(case.get("expectedTraceSteps"))
    expected_source_types = as_str_list(case.get("expectedSourceTypes"))
    expected_doc_ids = as_int_list(case.get("expectedSourceDocIds"))
    expected_keywords = as_str_list(case.get("expectedAnswerKeywords"))
    forbidden_keywords = as_str_list(case.get("forbidKeywords"))

    payload = {
        "title": case.get("title"),
        "description": case.get("description"),
        "brand": case.get("brand"),
        "conditionText": case.get("conditionText"),
        "categoryName": case.get("categoryName"),
        "expectedPrice": case.get("expectedPrice"),
    }
    url = base_url.rstrip("/") + "/api/ai/products/publish-assist"

    try:
        resp, latency_ms = post_json(url, payload, timeout_sec, bearer_token)
        app_code = resp.get("code")
        app_message = str(resp.get("message") or "")
        if app_code != 200:
            return empty_result(case_id, expected_source_types, expected_doc_ids, latency_ms, f"APP code={app_code}, message={app_message}")
        data = resp.get("data") or {}
        if not isinstance(data, dict) or not data:
            return empty_result(case_id, expected_source_types, expected_doc_ids, latency_ms, f"EMPTY data, APP code={app_code}, message={app_message}")

        generated_text = build_generated_text(data)
        source_text = build_source_text(data)
        eval_text = generated_text + "\n" + source_text
        trace = data.get("trace") or []
        if not isinstance(trace, list):
            trace = []
        trace_pass, steps, statuses = trace_ok(trace, expected_steps)
        returned_source_types, returned_doc_ids = extract_sources(data)
        doc_hit = True
        if expected_doc_ids:
            doc_hit = any(doc_id in returned_doc_ids for doc_id in expected_doc_ids)
        source_type_pass = contains_all_source_types(returned_source_types, expected_source_types)
        can_publish = data.get("canPublish")
        if not isinstance(can_publish, bool):
            can_publish = None
        risk_score = data.get("riskScore")
        if not isinstance(risk_score, int):
            risk_score = None
        detail_ok, detail_failures = detailed_assertions(
            case=case,
            data=data,
            generated_text=generated_text,
            eval_text=eval_text,
            trace=trace,
            trace_steps=steps,
            returned_source_types=returned_source_types,
            returned_doc_ids=returned_doc_ids,
            can_publish=can_publish,
            risk_score=risk_score,
        )

        return AgentCaseResult(
            case_id=case_id,
            trace_ok=trace_pass,
            source_type_ok=source_type_pass,
            keyword_hit_rate=keyword_hit_rate(eval_text, expected_keywords),
            source_doc_hit=doc_hit,
            block_ok=block_ok(data, bool(case.get("shouldBlock", False)), eval_text),
            clarification_ok=clarification_ok(eval_text, bool(case.get("shouldAskClarification", False))),
            forbidden_keyword_ok=forbidden_keyword_ok(generated_text, forbidden_keywords),
            quantified_advice_ok=quantified_advice_ok(generated_text, bool(case.get("forbidQuantifiedAdvice", False))),
            detail_assertions_ok=detail_ok,
            detail_failures=detail_failures,
            error="",
            latency_ms=latency_ms,
            trace_steps=steps,
            trace_statuses=statuses,
            returned_source_types=returned_source_types,
            returned_doc_ids=returned_doc_ids,
            expected_source_types=expected_source_types,
            expected_doc_ids=expected_doc_ids,
            can_publish=can_publish,
            risk_score=risk_score,
            answer_text=generated_text,
        )
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
        return empty_result(case_id, expected_source_types, expected_doc_ids, 0, str(exc))


def empty_result(
    case_id: str,
    expected_source_types: list[str],
    expected_doc_ids: list[int],
    latency_ms: float,
    error: str,
) -> AgentCaseResult:
    return AgentCaseResult(
        case_id=case_id,
        trace_ok=False,
        source_type_ok=False,
        keyword_hit_rate=0.0,
        source_doc_hit=False,
        block_ok=False,
        clarification_ok=False,
        forbidden_keyword_ok=False,
        quantified_advice_ok=False,
        detail_assertions_ok=False,
        detail_failures=[error] if error else [],
        error=error,
        latency_ms=latency_ms,
        trace_steps=[],
        trace_statuses=[],
        returned_source_types=[],
        returned_doc_ids=[],
        expected_source_types=expected_source_types,
        expected_doc_ids=expected_doc_ids,
        can_publish=None,
        risk_score=None,
        answer_text="",
    )


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    sorted_values = sorted(values)
    index = min(len(sorted_values) - 1, max(0, int(round((p / 100) * (len(sorted_values) - 1)))))
    return sorted_values[index]


def markdown_report(results: list[AgentCaseResult]) -> str:
    total = len(results)
    latencies = [r.latency_ms for r in results if r.latency_ms > 0]
    lines: list[str] = []
    lines.append("# Knowledge Agent Evaluation Report")
    lines.append("")
    lines.append(f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Cases: {total}")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Agent success rate: {sum(1 for r in results if r.ok) / total:.2%}")
    lines.append(f"- Trace pass rate: {sum(1 for r in results if r.trace_ok) / total:.2%}")
    lines.append(f"- Source type pass rate: {sum(1 for r in results if r.source_type_ok) / total:.2%}")
    lines.append(f"- Source doc hit rate: {sum(1 for r in results if r.source_doc_hit) / total:.2%}")
    lines.append(f"- Answer keyword hit rate avg: {sum(r.keyword_hit_rate for r in results) / total:.2%}")
    lines.append(f"- Block accuracy: {sum(1 for r in results if r.block_ok) / total:.2%}")
    lines.append(f"- Clarification accuracy: {sum(1 for r in results if r.clarification_ok) / total:.2%}")
    lines.append(f"- Forbidden keyword pass rate: {sum(1 for r in results if r.forbidden_keyword_ok) / total:.2%}")
    lines.append(f"- Quantified advice pass rate: {sum(1 for r in results if r.quantified_advice_ok) / total:.2%}")
    lines.append(f"- Detail assertion pass rate: {sum(1 for r in results if r.detail_assertions_ok) / total:.2%}")
    lines.append(f"- Errors: {sum(1 for r in results if r.error)}")
    lines.append(f"- P50 latency ms: {statistics.median(latencies) if latencies else 0:.1f}")
    lines.append(f"- P95 latency ms: {percentile(latencies, 95):.1f}")
    lines.append("")
    lines.append("## Failed Or Risky Cases")
    lines.append("")
    lines.append("| Case | OK | Trace | SrcType | DocHit | KW | Block | Clarify | NoForbidden | NoQuant | Detail | Risk | CanPublish | Returned Types | Returned DocIds | Detail Failures | Error |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|")
    for r in results:
        if r.ok:
            continue
        lines.append(
            f"| {r.case_id} | {str(r.ok).lower()} | {str(r.trace_ok).lower()} | {str(r.source_type_ok).lower()} | "
            f"{str(r.source_doc_hit).lower()} | {r.keyword_hit_rate:.2%} | {str(r.block_ok).lower()} | "
            f"{str(r.clarification_ok).lower()} | {str(r.forbidden_keyword_ok).lower()} | {str(r.quantified_advice_ok).lower()} | "
            f"{str(r.detail_assertions_ok).lower()} | "
            f"{r.risk_score if r.risk_score is not None else ''} | {r.can_publish if r.can_publish is not None else ''} | "
            f"{r.returned_source_types} | {r.returned_doc_ids} | `{';'.join(r.detail_failures)[:160]}` | `{r.error[:120]}` |"
        )
    lines.append("")
    lines.append("## Per Case")
    lines.append("")
    lines.append("| Case | OK | Trace Steps | Trace Statuses | Sources | Risk | CanPublish | Latency ms | Answer Preview |")
    lines.append("|---|---:|---|---|---|---:|---:|---:|---|")
    for r in results:
        preview = r.answer_text.replace("|", "\\|").replace("\n", " ")[:220]
        lines.append(
            f"| {r.case_id} | {str(r.ok).lower()} | {r.trace_steps} | {r.trace_statuses} | "
            f"{r.returned_doc_ids} | {r.risk_score if r.risk_score is not None else ''} | "
            f"{r.can_publish if r.can_publish is not None else ''} | {r.latency_ms:.1f} | {preview} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate Knowledge Agent orchestration.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/agent_eval_dataset.json")
    parser.add_argument("--timeout-sec", type=float, default=120)
    add_auth_args(parser)
    parser.add_argument("--out", default="")
    args = parser.parse_args()
    args.bearer_token = resolve_bearer_token(args, args.base_url, args.timeout_sec)

    cases = load_json(Path(args.dataset))
    if not isinstance(cases, list):
        print("Dataset must be a JSON array.", file=sys.stderr)
        return 2

    results: list[AgentCaseResult] = []
    for case in cases:
        result = evaluate_case(args.base_url, case, args.timeout_sec, args.bearer_token)
        results.append(result)
        status = "OK" if result.ok else "RISK"
        print(
            f"{result.case_id:12s} {status:4s} trace={result.trace_ok} srcType={result.source_type_ok} "
            f"doc={result.source_doc_hit} kw={result.keyword_hit_rate:.0%} block={result.block_ok} "
            f"noQuant={result.quantified_advice_ok} detail={result.detail_assertions_ok} {result.latency_ms:.1f}ms"
        )

    report = markdown_report(results)
    if args.out:
        out_path = Path(args.out)
    else:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        out_path = Path(f"agent_eval_report_{stamp}.md")
    out_path.write_text(report, encoding="utf-8")
    print(f"\nReport written: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
