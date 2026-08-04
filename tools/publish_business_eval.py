#!/usr/bin/env python3
"""Evaluate publish fact preservation and target risk decisions.

This is a target-business evaluation rather than a characterization test. Failures
may expose known gaps in negation handling, hard-block decisions, or evidence dedup.
"""
from __future__ import annotations

import argparse
import json
import math
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from eval_auth import add_auth_args, resolve_bearer_token


@dataclass
class CaseResult:
    case_id: str
    ok: bool
    latency_ms: float
    fact_correct: int
    fact_total: int
    can_publish: bool | None
    rule_codes: list[str]
    details: list[str]


def request_json(url: str, payload: dict[str, Any], token: str,
                 timeout_sec: float) -> tuple[dict[str, Any], float]:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json; charset=utf-8",
        },
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
    return json.loads(raw), (time.perf_counter() - started) * 1000


def normalized_text(data: dict[str, Any]) -> str:
    optimize = data.get("optimizeResult") if isinstance(data.get("optimizeResult"), dict) else {}
    warnings = data.get("riskWarnings") if isinstance(data.get("riskWarnings"), list) else []
    values = [
        optimize.get("optimizedTitle"),
        optimize.get("optimizedDescription"),
        " ".join(str(value) for value in optimize.get("tags") or []),
        " ".join(str(value) for value in warnings),
    ]
    return " ".join(str(value or "") for value in values).lower().replace(" ", "")


def evaluate(case: dict[str, Any], base_url: str, token: str,
             timeout_sec: float) -> CaseResult:
    case_id = str(case.get("caseId") or "UNKNOWN")
    failures: list[str] = []
    latency_ms = 0.0
    try:
        response, latency_ms = request_json(
            base_url.rstrip("/") + "/api/ai/products/publish-assist",
            case.get("input") or {}, token, timeout_sec,
        )
        if int(response.get("code") or 0) != 200:
            raise AssertionError(f"appCode={response.get('code')},response={response}")
        data = response.get("data") if isinstance(response.get("data"), dict) else {}
        output_text = normalized_text(data)

        fact_total = 0
        fact_correct = 0
        for fact in case.get("expectedFacts") or []:
            fact_total += 1
            aliases = [str(value).lower().replace(" ", "") for value in fact.get("anyOf") or []]
            if any(alias and alias in output_text for alias in aliases):
                fact_correct += 1
            else:
                failures.append(f"missingFact:{fact.get('field')}={fact.get('anyOf')}")

        evidence = data.get("riskEvidence") if isinstance(data.get("riskEvidence"), list) else []
        rule_codes = [str(item.get("ruleCode") or "") for item in evidence if isinstance(item, dict)]
        for expected in case.get("expectedRuleCodes") or []:
            if str(expected) not in rule_codes:
                failures.append(f"missingRule:{expected}")
        for forbidden in case.get("forbiddenRuleCodes") or []:
            if str(forbidden) in rule_codes:
                failures.append(f"falsePositiveRule:{forbidden}")
        for code, maximum in (case.get("maxRuleOccurrences") or {}).items():
            count = rule_codes.count(str(code))
            if count > int(maximum):
                failures.append(f"duplicateRule:{code}={count}>{maximum}")

        actual_can_publish = data.get("canPublish")
        expected_can_publish = case.get("expectedCanPublish")
        if isinstance(expected_can_publish, bool) and actual_can_publish is not expected_can_publish:
            failures.append(f"canPublish:{actual_can_publish}!={expected_can_publish}")

        if not data.get("assistId") or not data.get("traceId"):
            failures.append("missingTraceability")
        if not evidence and (case.get("expectedRuleCodes") or []):
            failures.append("missingRiskEvidence")
        return CaseResult(case_id, not failures, latency_ms, fact_correct, fact_total,
                          actual_can_publish if isinstance(actual_can_publish, bool) else None,
                          rule_codes, failures)
    except Exception as exc:
        return CaseResult(case_id, False, latency_ms, 0,
                          len(case.get("expectedFacts") or []), None, [], [f"error:{exc}"])


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    position = (len(ordered) - 1) * p / 100
    low, high = math.floor(position), math.ceil(position)
    if low == high:
        return ordered[low]
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate publish facts and target risk decisions.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/publish_business_eval_dataset.json")
    parser.add_argument("--output", default="eval/reports/publish_business_eval_report.md")
    parser.add_argument("--timeout-sec", type=float, default=90)
    add_auth_args(parser)
    args = parser.parse_args()
    token = resolve_bearer_token(args, args.base_url, args.timeout_sec)
    if not token:
        raise SystemExit("A USER bearer token or login credentials are required.")

    cases = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    results = [evaluate(case, args.base_url, token, args.timeout_sec) for case in cases]
    for result in results:
        print(f"[{'OK' if result.ok else 'RISK'}] {result.case_id} "
              f"facts={result.fact_correct}/{result.fact_total} canPublish={result.can_publish} "
              f"latency={result.latency_ms:.1f} details={result.details}")

    passed = sum(result.ok for result in results)
    fact_correct = sum(result.fact_correct for result in results)
    fact_total = sum(result.fact_total for result in results)
    result_by_id = {result.case_id: result for result in results}
    hard_expected = 0
    hard_hit = 0
    safe_cases = 0
    safe_false_positives = 0
    dedup_checks = 0
    dedup_passed = 0
    for case in cases:
        result = result_by_id.get(str(case.get("caseId") or "UNKNOWN"))
        if result is None:
            continue
        expected_rules = [str(code) for code in case.get("expectedRuleCodes") or []]
        hard_rules = [code for code in expected_rules if code.startswith("HIGH_")]
        hard_expected += len(hard_rules)
        hard_hit += sum(code in result.rule_codes for code in hard_rules)
        if case.get("expectedCanPublish") is True:
            safe_cases += 1
            forbidden = [str(code) for code in case.get("forbiddenRuleCodes") or []]
            if result.can_publish is not True or any(code in result.rule_codes for code in forbidden):
                safe_false_positives += 1
        for code, maximum in (case.get("maxRuleOccurrences") or {}).items():
            dedup_checks += 1
            if result.rule_codes.count(str(code)) <= int(maximum):
                dedup_passed += 1
    output = Path(args.output)
    lines = [
        "# Publish Business Target Eval Report",
        "",
        f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"- Cases: {len(results)}",
        f"- Passed: {passed}",
        f"- Pass rate: {passed / len(results):.2%}" if results else "- Pass rate: 0.00%",
        f"- Fact preservation: {fact_correct / fact_total:.2%}" if fact_total else "- Fact preservation: N/A",
        f"- Hard-risk blocking recall: {hard_hit / hard_expected:.2%}" if hard_expected else "- Hard-risk blocking recall: N/A",
        f"- Safe-example false-positive rate: {safe_false_positives / safe_cases:.2%}" if safe_cases else "- Safe-example false-positive rate: N/A",
        f"- Rule dedup pass rate: {dedup_passed / dedup_checks:.2%}" if dedup_checks else "- Rule dedup pass rate: N/A",
        f"- P95 latency: {percentile([result.latency_ms for result in results], 95):.1f} ms",
        "",
        "> This report evaluates target business behavior; failures must not be hidden by weakening assertions.",
        "",
        "| Case | Status | Facts | canPublish | Rule codes | Latency(ms) | Details |",
        "| --- | --- | ---: | --- | --- | ---: | --- |",
    ]
    lines.extend(
        f"| {result.case_id} | {'PASS' if result.ok else 'FAIL'} | "
        f"{result.fact_correct}/{result.fact_total} | {result.can_publish} | "
        f"{','.join(result.rule_codes)} | {result.latency_ms:.1f} | {'; '.join(result.details)} |"
        for result in results
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"written to {output}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
