#!/usr/bin/env python3
"""Evaluate deterministic Token budget and priority trimming without calling an LLM."""
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

from eval_auth import strip_bearer_prefix


@dataclass
class CaseResult:
    case_id: str
    scene: str
    ok: bool
    latency_ms: float
    prompt_tokens: int
    budget: int
    trimmed_tokens: int
    details: list[str]


def post_json(base_url: str, path: str, body: dict[str, Any], token: str,
              timeout_sec: float) -> tuple[dict[str, Any], float]:
    payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=payload,
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


def expand_sections(case: dict[str, Any]) -> list[dict[str, Any]]:
    sections: list[dict[str, Any]] = []
    for source in case.get("sections") or []:
        section = dict(source)
        repeat = max(1, int(section.pop("repeat", 1)))
        section["content"] = str(section.get("content") or "") * repeat
        sections.append(section)
    return sections


def evaluate(case: dict[str, Any], base_url: str, token: str,
             timeout_sec: float) -> CaseResult:
    details: list[str] = []
    scene = str(case.get("scene") or "GENERAL")
    try:
        response, latency_ms = post_json(
            base_url,
            "/api/ai/token-governance/preview",
            {"scene": scene, "sections": expand_sections(case)},
            token,
            timeout_sec,
        )
        if int(response.get("code") or 0) != 200:
            raise AssertionError(f"appCode={response.get('code')},response={response}")
        data = response.get("data") or {}
        prompt = str(data.get("prompt") or "")
        prompt_tokens = int(data.get("promptTokens") or 0)
        budget = int(data.get("inputBudget") or 0)
        trimmed_tokens = int(data.get("trimmedTokens") or 0)
        trimmed_sections = set(data.get("trimmedSections") or [])
        if prompt_tokens > budget:
            details.append(f"budgetExceeded:{prompt_tokens}>{budget}")
        if bool(data.get("trimmed")) != bool(case.get("expectTrimmed")):
            details.append(f"trimmed:{data.get('trimmed')}!={case.get('expectTrimmed')}")
        for keyword in case.get("expectedPresent") or []:
            if str(keyword) not in prompt:
                details.append(f"missingProtectedKeyword:{keyword}")
        for section in case.get("expectedTrimmedSections") or []:
            if str(section) not in trimmed_sections:
                details.append(f"sectionNotTrimmed:{section}")
        return CaseResult(str(case["caseId"]), scene, not details, latency_ms,
                          prompt_tokens, budget, trimmed_tokens, details)
    except Exception as exc:
        return CaseResult(str(case["caseId"]), scene, False, 0.0, 0, 0, 0, [str(exc)])


def percentile(values: list[float], p: float) -> float:
    values = sorted(values)
    if not values:
        return 0.0
    pos = (len(values) - 1) * p / 100
    low, high = math.floor(pos), math.ceil(pos)
    return values[low] if low == high else values[low] + (values[high] - values[low]) * (pos - low)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/token_governance_eval_dataset.json")
    parser.add_argument("--output", default="")
    parser.add_argument("--timeout-sec", type=float, default=15)
    parser.add_argument("--internal-bearer-token", required=True)
    args = parser.parse_args()

    cases = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    token = strip_bearer_prefix(args.internal_bearer_token)
    results = [evaluate(case, args.base_url, token, args.timeout_sec) for case in cases]
    for result in results:
        status = "OK" if result.ok else "RISK"
        print(f"[{status}] {result.case_id} scene={result.scene} "
              f"tokens={result.prompt_tokens}/{result.budget} trimmed={result.trimmed_tokens} "
              f"latency={result.latency_ms:.1f} details={result.details}")

    passed = sum(result.ok for result in results)
    output = Path(args.output) if args.output else Path(
        f"eval/reports/token_governance_eval_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md"
    )
    lines = [
        "# Token Governance Eval Report",
        "",
        f"- Cases: {len(results)}",
        f"- Passed: {passed}",
        f"- Pass rate: {passed / len(results):.2%}",
        f"- P95 latency: {percentile([result.latency_ms for result in results], 95):.1f} ms",
        f"- Total trimmed tokens: {sum(result.trimmed_tokens for result in results)}",
        "",
        "| Case | Scene | Status | Prompt/Budget | Trimmed | Latency(ms) | Details |",
        "| --- | --- | --- | ---: | ---: | ---: | --- |",
    ]
    lines.extend(
        f"| {result.case_id} | {result.scene} | {'PASS' if result.ok else 'FAIL'} | "
        f"{result.prompt_tokens}/{result.budget} | {result.trimmed_tokens} | "
        f"{result.latency_ms:.1f} | {'; '.join(result.details)} |"
        for result in results
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"written to {output}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
