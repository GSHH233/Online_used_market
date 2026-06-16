#!/usr/bin/env python3
"""
Evaluate ReAct-style Tool Agent quality.

Example:
  python tools/react_agent_eval.py ^
    --base-url http://localhost:8080 ^
    --dataset eval/datasets/react_agent_eval_dataset.json ^
    --bearer-token <JWT>
"""

from __future__ import annotations

import argparse
import json
import os
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


@dataclass
class ReactCaseResult:
    case_id: str
    tool_ok: bool
    denied_ok: bool
    trace_ok: bool
    observation_ok: bool
    source_ok: bool
    answer_ok: bool
    forbidden_ok: bool
    latency_ok: bool
    error: str
    latency_ms: float
    returned_tools: list[str]
    denied_tools: list[str]
    trace_phases: list[str]
    trace_statuses: list[str]
    returned_doc_ids: list[int]
    answer_text: str
    detail_failures: list[str]

    @property
    def ok(self) -> bool:
        return (
            self.tool_ok
            and self.denied_ok
            and self.trace_ok
            and self.observation_ok
            and self.source_ok
            and self.answer_ok
            and self.forbidden_ok
            and self.latency_ok
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


def extract_tools(data: dict[str, Any]) -> tuple[list[str], list[str], list[str], list[str]]:
    tools: list[str] = []
    denied: list[str] = []
    phases: list[str] = []
    statuses: list[str] = []
    for step in data.get("agentTrace") or []:
        if not isinstance(step, dict):
            continue
        phase = str(step.get("phase") or "")
        status = str(step.get("status") or "")
        tool = str(step.get("tool") or "")
        phases.append(phase)
        statuses.append(status)
        if tool:
            tools.append(tool)
            if status.upper() == "DENIED":
                denied.append(tool)
    for obs in data.get("observations") or []:
        if isinstance(obs, dict) and obs.get("tool"):
            tool = str(obs.get("tool"))
            if tool not in tools:
                tools.append(tool)
    return tools, denied, phases, statuses


def extract_doc_ids(data: dict[str, Any]) -> list[int]:
    ids: list[int] = []
    for source in data.get("sources") or []:
        if isinstance(source, dict):
            doc_id = source.get("docId")
            if isinstance(doc_id, int):
                ids.append(doc_id)
    for obs in data.get("observations") or []:
        if not isinstance(obs, dict):
            continue
        for row in obs.get("rows") or []:
            if isinstance(row, dict):
                doc_id = row.get("docId")
                if isinstance(doc_id, int):
                    ids.append(doc_id)
    return list(dict.fromkeys(ids))


def build_observation_text(data: dict[str, Any]) -> str:
    parts: list[str] = []
    for obs in data.get("observations") or []:
        if not isinstance(obs, dict):
            continue
        parts.append(compact_text(obs.get("tool")))
        parts.append(compact_text(obs.get("summary")))
        for row in obs.get("rows") or []:
            parts.append(compact_text(row))
    return "\n".join(x for x in parts if x)


def contains_all(haystack_items: list[str], expected: list[str]) -> bool:
    returned = {x.lower() for x in haystack_items}
    return all(x.lower() in returned for x in expected)


def keyword_hit_ok(text: str, keywords: list[str]) -> bool:
    if not keywords:
        return True
    lower = text.lower()
    return all(kw.lower() in lower for kw in keywords)


def evaluate_case(base_url: str, case: dict[str, Any], timeout_sec: float, bearer_token: str) -> ReactCaseResult:
    case_id = str(case.get("caseId", "UNKNOWN"))
    payload = {
        "sessionId": case.get("sessionId", "react-eval"),
        "traceId": case.get("traceId", ""),
        "mode": case.get("mode", "USER"),
        "question": case.get("question", ""),
        "maxSteps": case.get("maxSteps", 5),
    }
    url = base_url.rstrip("/") + "/api/ai/agent/react-chat"
    expected_tools = as_str_list(case.get("expectedTools"))
    expected_denied_tools = as_str_list(case.get("expectedDeniedTools"))
    forbidden_tools = as_str_list(case.get("forbidTools"))
    expected_doc_ids = as_int_list(case.get("expectedSourceDocIds"))
    detail_failures: list[str] = []

    try:
        resp, latency_ms = post_json(url, payload, timeout_sec, bearer_token)
        app_code = resp.get("code")
        app_message = str(resp.get("message") or "")
        if app_code != 200:
            return empty_result(case_id, latency_ms, f"APP code={app_code}, message={app_message}")
        data = resp.get("data") or {}
        if not isinstance(data, dict) or not data:
            return empty_result(case_id, latency_ms, f"EMPTY data, APP code={app_code}, message={app_message}")

        returned_tools, denied_tools, phases, statuses = extract_tools(data)
        doc_ids = extract_doc_ids(data)
        answer = compact_text(data.get("answer"))
        observation_text = build_observation_text(data)
        eval_text = answer + "\n" + observation_text

        tool_ok = contains_all(returned_tools, expected_tools)
        if not tool_ok:
            detail_failures.append("missingTools:" + ",".join(sorted(set(expected_tools) - {x for x in returned_tools})))
        forbidden_tool_ok = not any(tool in returned_tools for tool in forbidden_tools)
        if not forbidden_tool_ok:
            detail_failures.append("forbiddenToolUsed:" + ",".join(forbidden_tools))
        tool_ok = tool_ok and forbidden_tool_ok

        denied_ok = contains_all(denied_tools, expected_denied_tools)
        if not denied_ok:
            detail_failures.append("missingDeniedTools:" + ",".join(sorted(set(expected_denied_tools) - {x for x in denied_tools})))

        min_trace = int(case.get("minTraceSteps", 1))
        trace_ok = len(phases) >= min_trace and "plan" in phases and "final" in phases and all(status for status in statuses)
        if not trace_ok:
            detail_failures.append(f"traceInvalid:len={len(phases)},phases={phases},statuses={statuses}")

        observation_ok = True
        if bool(case.get("requireObservation", False)):
            observation_ok = bool(data.get("observations"))
            if not observation_ok:
                detail_failures.append("emptyObservation")

        source_ok = True
        if bool(case.get("requireSources", False)):
            source_ok = bool(doc_ids)
            if not source_ok:
                detail_failures.append("emptySources")
        if expected_doc_ids:
            hit = any(doc_id in doc_ids for doc_id in expected_doc_ids)
            source_ok = source_ok and hit
            if not hit:
                detail_failures.append(f"missingDocIds:{expected_doc_ids}")

        expected_answer_keywords = as_str_list(case.get("expectedAnswerKeywords"))
        missing_answer_keywords = [
            kw for kw in expected_answer_keywords
            if kw.lower() not in eval_text.lower()
        ]
        answer_ok = not missing_answer_keywords
        if not answer_ok:
            detail_failures.append("missingAnswerKeywords:" + ",".join(missing_answer_keywords))

        forbidden_ok = not any(kw.lower() in answer.lower() for kw in as_str_list(case.get("forbidAnswerKeywords")))
        if not forbidden_ok:
            detail_failures.append("forbiddenAnswerKeyword")

        max_latency_ms = float(case.get("maxLatencyMs") or 0)
        latency_ok = max_latency_ms <= 0 or latency_ms <= max_latency_ms
        if not latency_ok:
            detail_failures.append(f"latencyExceeded:{latency_ms:.1f}>{max_latency_ms:.1f}")

        return ReactCaseResult(
            case_id=case_id,
            tool_ok=tool_ok,
            denied_ok=denied_ok,
            trace_ok=trace_ok,
            observation_ok=observation_ok,
            source_ok=source_ok,
            answer_ok=answer_ok,
            forbidden_ok=forbidden_ok,
            latency_ok=latency_ok,
            error="",
            latency_ms=latency_ms,
            returned_tools=returned_tools,
            denied_tools=denied_tools,
            trace_phases=phases,
            trace_statuses=statuses,
            returned_doc_ids=doc_ids,
            answer_text=answer,
            detail_failures=detail_failures,
        )
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
        return empty_result(case_id, 0, str(exc))


def empty_result(case_id: str, latency_ms: float, error: str) -> ReactCaseResult:
    return ReactCaseResult(
        case_id=case_id,
        tool_ok=False,
        denied_ok=False,
        trace_ok=False,
        observation_ok=False,
        source_ok=False,
        answer_ok=False,
        forbidden_ok=False,
        latency_ok=False,
        error=error,
        latency_ms=latency_ms,
        returned_tools=[],
        denied_tools=[],
        trace_phases=[],
        trace_statuses=[],
        returned_doc_ids=[],
        answer_text="",
        detail_failures=[error] if error else [],
    )


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    sorted_values = sorted(values)
    index = min(len(sorted_values) - 1, max(0, int(round((p / 100) * (len(sorted_values) - 1)))))
    return sorted_values[index]


def markdown_report(results: list[ReactCaseResult]) -> str:
    total = len(results)
    latencies = [r.latency_ms for r in results if r.latency_ms > 0]
    lines: list[str] = []
    lines.append("# ReAct Agent Evaluation Report")
    lines.append("")
    lines.append(f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Cases: {total}")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Success rate: {sum(1 for r in results if r.ok) / total:.2%}")
    lines.append(f"- Tool selection accuracy: {sum(1 for r in results if r.tool_ok) / total:.2%}")
    lines.append(f"- Denied tool accuracy: {sum(1 for r in results if r.denied_ok) / total:.2%}")
    lines.append(f"- Trace completeness: {sum(1 for r in results if r.trace_ok) / total:.2%}")
    lines.append(f"- Observation pass rate: {sum(1 for r in results if r.observation_ok) / total:.2%}")
    lines.append(f"- Source coverage rate: {sum(1 for r in results if r.source_ok) / total:.2%}")
    lines.append(f"- Final answer keyword rate: {sum(1 for r in results if r.answer_ok) / total:.2%}")
    lines.append(f"- Forbidden answer pass rate: {sum(1 for r in results if r.forbidden_ok) / total:.2%}")
    lines.append(f"- Latency budget pass rate: {sum(1 for r in results if r.latency_ok) / total:.2%}")
    lines.append(f"- Errors: {sum(1 for r in results if r.error)}")
    lines.append(f"- P50 latency ms: {statistics.median(latencies) if latencies else 0:.1f}")
    lines.append(f"- P95 latency ms: {percentile(latencies, 95):.1f}")
    lines.append("")
    lines.append("## Failed Or Risky Cases")
    lines.append("")
    lines.append("| Case | OK | Tool | Denied | Trace | Obs | Source | Answer | Forbidden | Latency | Tools | Denied Tools | DocIds | Detail Failures | Error |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---|")
    for r in results:
        if r.ok:
            continue
        lines.append(
            f"| {r.case_id} | {str(r.ok).lower()} | {str(r.tool_ok).lower()} | {str(r.denied_ok).lower()} | "
            f"{str(r.trace_ok).lower()} | {str(r.observation_ok).lower()} | {str(r.source_ok).lower()} | "
            f"{str(r.answer_ok).lower()} | {str(r.forbidden_ok).lower()} | {str(r.latency_ok).lower()} | {r.returned_tools} | {r.denied_tools} | "
            f"{r.returned_doc_ids} | `{';'.join(r.detail_failures)[:180]}` | `{r.error[:120]}` |"
        )
    lines.append("")
    lines.append("## Slowest Cases")
    lines.append("")
    lines.append("| Case | OK | Latency ms | Tools |")
    lines.append("|---|---:|---:|---|")
    for r in sorted(results, key=lambda item: item.latency_ms, reverse=True)[:5]:
        lines.append(f"| {r.case_id} | {str(r.ok).lower()} | {r.latency_ms:.1f} | {r.returned_tools} |")
    lines.append("")
    lines.append("## Per Case")
    lines.append("")
    lines.append("| Case | OK | Tools | Phases | Statuses | DocIds | Latency ms | Answer Preview |")
    lines.append("|---|---:|---|---|---|---|---:|---|")
    for r in results:
        preview = r.answer_text.replace("|", "\\|").replace("\n", " ")[:220]
        lines.append(
            f"| {r.case_id} | {str(r.ok).lower()} | {r.returned_tools} | {r.trace_phases} | "
            f"{r.trace_statuses} | {r.returned_doc_ids} | {r.latency_ms:.1f} | {preview} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate ReAct-style Tool Agent.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/react_agent_eval_dataset.json")
    parser.add_argument("--timeout-sec", type=float, default=120)
    add_auth_args(parser)
    parser.add_argument("--out", default="")
    args = parser.parse_args()
    args.bearer_token = resolve_bearer_token(args, args.base_url, args.timeout_sec)

    cases = load_json(Path(args.dataset))
    if not isinstance(cases, list):
        print("Dataset must be a JSON array.", file=sys.stderr)
        return 2

    results: list[ReactCaseResult] = []
    for case in cases:
        result = evaluate_case(args.base_url, case, args.timeout_sec, args.bearer_token)
        results.append(result)
        status = "OK" if result.ok else "RISK"
        print(
            f"{result.case_id:12s} {status:4s} tool={result.tool_ok} denied={result.denied_ok} "
            f"trace={result.trace_ok} obs={result.observation_ok} source={result.source_ok} "
            f"answer={result.answer_ok} {result.latency_ms:.1f}ms"
        )

    report = markdown_report(results)
    if args.out:
        out_path = Path(args.out)
    else:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        out_path = Path(f"react_agent_eval_report_{stamp}.md")
    out_path.write_text(report, encoding="utf-8")
    print(f"\nReport written: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
