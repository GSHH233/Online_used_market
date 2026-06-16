#!/usr/bin/env python3
"""
Evaluate ToolExecutionHarness retry / timeout / fallback / snapshot behavior.

Example:
  python tools/harness_eval.py ^
    --base-url http://localhost:8080 ^
    --dataset eval/datasets/harness_eval_dataset.json ^
    --bearer-token <JWT>
"""

from __future__ import annotations

import argparse
import json
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
class HarnessCaseResult:
    case_id: str
    ok: bool
    status_ok: bool
    attempts_ok: bool
    retry_ok: bool
    fallback_ok: bool
    snapshot_ok: bool
    timeout_ok: bool
    metadata_ok: bool
    denied_ok: bool
    observation_ok: bool
    latency_ok: bool
    app_code: int
    status: str
    attempts: int
    retry_count: int
    fallback_used: bool
    snapshot_restored: bool
    timeout: bool
    latency_ms: float
    detail_failures: list[str]
    error: str


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def render_template(value: Any, run_id: str) -> Any:
    if isinstance(value, dict):
        return {k: render_template(v, run_id) for k, v in value.items()}
    if isinstance(value, list):
        return [render_template(v, run_id) for v in value]
    if isinstance(value, str):
        return value.replace("${runId}", run_id)
    return value


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
    except urllib.error.HTTPError as ex:
        raw = ex.read().decode("utf-8")
    finally:
        latency_ms = (time.perf_counter() - start) * 1000
    return json.loads(raw), latency_ms


def compact_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False)


def text_contains_all(text: str, expected: list[str]) -> bool:
    lower = text.lower()
    return all(str(x).lower() in lower for x in expected)


def as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def evaluate_case(base_url: str, case: dict[str, Any], timeout_sec: float, bearer_token: str, run_id: str) -> HarnessCaseResult:
    case_id = str(case.get("caseId") or "")
    payload = {
        "tool": case.get("tool"),
        "mode": case.get("mode", "INTERNAL"),
        "input": render_template(case.get("input") or {}, run_id),
    }
    try:
        resp, latency_ms = post_json(base_url.rstrip("/") + "/api/ai/harness/debug/execute", payload, timeout_sec, bearer_token)
    except Exception as exc:
        return empty_result(case_id, str(exc))

    app_code = int(resp.get("code") or 0)
    data = resp.get("data") if isinstance(resp.get("data"), dict) else {}
    metadata = data.get("metadata") if isinstance(data.get("metadata"), dict) else {}
    observation = data.get("observation") if isinstance(data.get("observation"), dict) else {}
    eval_text = compact_text(resp)

    expected_app_code = int(case.get("expectedAppCode") or 200)
    expected_denied = bool(case.get("expectedDenied", False))
    denied_ok = (app_code == expected_app_code) if expected_denied else True
    failures: list[str] = []
    if app_code != expected_app_code:
        failures.append(f"appCode:{app_code}!={expected_app_code}")

    if expected_denied:
        latency_pass = latency_ok(case, latency_ms, failures)
        ok = app_code == expected_app_code and denied_ok and latency_pass
        return HarnessCaseResult(
            case_id=case_id,
            ok=ok,
            status_ok=ok,
            attempts_ok=ok,
            retry_ok=ok,
            fallback_ok=ok,
            snapshot_ok=ok,
            timeout_ok=ok,
            metadata_ok=ok,
            denied_ok=denied_ok,
            observation_ok=ok,
            latency_ok=latency_pass,
            app_code=app_code,
            status="DENIED",
            attempts=0,
            retry_count=0,
            fallback_used=False,
            snapshot_restored=False,
            timeout=False,
            latency_ms=latency_ms,
            detail_failures=failures,
            error="",
        )

    status = str(data.get("status") or "")
    attempts = int(data.get("attempts") or 0)
    retry_count = int(data.get("retryCount") or 0)
    fallback_used = bool(data.get("fallbackUsed", False))
    snapshot_restored = bool(data.get("snapshotRestored", False))
    timeout = bool(data.get("timeout", False))

    status_ok = status == str(case.get("expectedStatus") or "")
    if not status_ok:
        failures.append(f"status:{status}!={case.get('expectedStatus')}")

    expected_attempts = int(case["expectedAttempts"]) if "expectedAttempts" in case else attempts
    attempts_ok = attempts == expected_attempts
    if not attempts_ok:
        failures.append(f"attempts:{attempts}!={expected_attempts}")

    expected_retry = int(case["expectedRetryCount"]) if "expectedRetryCount" in case else retry_count
    retry_ok = retry_count == expected_retry
    if not retry_ok:
        failures.append(f"retryCount:{retry_count}!={expected_retry}")

    fallback_ok = fallback_used == bool(case.get("expectedFallbackUsed", fallback_used))
    if not fallback_ok:
        failures.append(f"fallbackUsed:{fallback_used}!={case.get('expectedFallbackUsed')}")

    snapshot_ok = snapshot_restored == bool(case.get("expectedSnapshotRestored", snapshot_restored))
    if not snapshot_ok:
        failures.append(f"snapshotRestored:{snapshot_restored}!={case.get('expectedSnapshotRestored')}")

    timeout_ok = timeout == bool(case.get("expectedTimeout", timeout))
    if not timeout_ok:
        failures.append(f"timeout:{timeout}!={case.get('expectedTimeout')}")

    metadata_ok = bool(metadata.get("harness")) and str(metadata.get("status") or "") == status
    for key in ["attempts", "retryCount", "timeout", "fallbackUsed", "snapshotRestored", "elapsedMs"]:
        metadata_ok = metadata_ok and key in metadata
    if not metadata_ok:
        failures.append("metadataInvalid")

    expected_keywords = [str(x) for x in as_list(case.get("expectedObservationKeywords"))]
    observation_ok = text_contains_all(compact_text(observation), expected_keywords)
    if not observation_ok:
        failures.append("observationMissingKeywords:" + ",".join(expected_keywords))

    latency_pass = latency_ok(case, latency_ms, failures)
    ok = all([app_code == expected_app_code, status_ok, attempts_ok, retry_ok, fallback_ok, snapshot_ok, timeout_ok, metadata_ok, denied_ok, observation_ok, latency_pass])
    return HarnessCaseResult(
        case_id=case_id,
        ok=ok,
        status_ok=status_ok,
        attempts_ok=attempts_ok,
        retry_ok=retry_ok,
        fallback_ok=fallback_ok,
        snapshot_ok=snapshot_ok,
        timeout_ok=timeout_ok,
        metadata_ok=metadata_ok,
        denied_ok=denied_ok,
        observation_ok=observation_ok,
        latency_ok=latency_pass,
        app_code=app_code,
        status=status,
        attempts=attempts,
        retry_count=retry_count,
        fallback_used=fallback_used,
        snapshot_restored=snapshot_restored,
        timeout=timeout,
        latency_ms=latency_ms,
        detail_failures=failures,
        error="",
    )


def latency_ok(case: dict[str, Any], latency_ms: float, failures: list[str]) -> bool:
    max_latency = float(case.get("maxLatencyMs") or 0)
    ok = max_latency <= 0 or latency_ms <= max_latency
    if not ok:
        failures.append(f"latencyExceeded:{latency_ms:.1f}>{max_latency:.1f}")
    return ok


def empty_result(case_id: str, error: str) -> HarnessCaseResult:
    return HarnessCaseResult(
        case_id=case_id,
        ok=False,
        status_ok=False,
        attempts_ok=False,
        retry_ok=False,
        fallback_ok=False,
        snapshot_ok=False,
        timeout_ok=False,
        metadata_ok=False,
        denied_ok=False,
        observation_ok=False,
        latency_ok=False,
        app_code=0,
        status="",
        attempts=0,
        retry_count=0,
        fallback_used=False,
        snapshot_restored=False,
        timeout=False,
        latency_ms=0,
        detail_failures=[],
        error=error,
    )


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, int(round((pct / 100) * (len(ordered) - 1)))))
    return ordered[idx]


def rate(results: list[HarnessCaseResult], attr: str) -> float:
    return sum(1 for r in results if getattr(r, attr)) / len(results) if results else 0.0


def write_report(results: list[HarnessCaseResult], output: Path, run_id: str) -> None:
    latencies = [r.latency_ms for r in results if r.latency_ms > 0]
    lines: list[str] = []
    lines.append("# Harness Evaluation Report")
    lines.append("")
    lines.append(f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Run ID: {run_id}")
    lines.append(f"- Cases: {len(results)}")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Success rate: {rate(results, 'ok'):.2%}")
    lines.append(f"- Status pass rate: {rate(results, 'status_ok'):.2%}")
    lines.append(f"- Retry pass rate: {rate(results, 'retry_ok'):.2%}")
    lines.append(f"- Timeout pass rate: {rate(results, 'timeout_ok'):.2%}")
    lines.append(f"- Fallback pass rate: {rate(results, 'fallback_ok'):.2%}")
    lines.append(f"- Snapshot restore pass rate: {rate(results, 'snapshot_ok'):.2%}")
    lines.append(f"- Metadata pass rate: {rate(results, 'metadata_ok'):.2%}")
    lines.append(f"- Denied pass rate: {rate(results, 'denied_ok'):.2%}")
    lines.append(f"- Latency budget pass rate: {rate(results, 'latency_ok'):.2%}")
    lines.append(f"- Errors: {sum(1 for r in results if r.error)}")
    lines.append(f"- P50 latency ms: {statistics.median(latencies) if latencies else 0:.1f}")
    lines.append(f"- P95 latency ms: {percentile(latencies, 95):.1f}")
    lines.append("")
    lines.append("## Failed Or Risky Cases")
    lines.append("")
    lines.append("| Case | OK | App | Status | Attempts | Retry | Timeout | Fallback | Snapshot | Metadata | Latency | Details | Error |")
    lines.append("|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---|---|")
    for r in results:
        if r.ok and not r.error:
            continue
        details = ";".join(r.detail_failures)
        lines.append(
            f"| {r.case_id} | {str(r.ok).lower()} | {r.app_code} | {r.status} | {r.attempts} | {r.retry_count} | "
            f"{str(r.timeout).lower()} | {str(r.fallback_used).lower()} | {str(r.snapshot_restored).lower()} | "
            f"{str(r.metadata_ok).lower()} | {r.latency_ms:.1f} | `{details}` | `{r.error}` |"
        )
    lines.append("")
    lines.append("## Slowest Cases")
    lines.append("")
    lines.append("| Case | OK | Latency ms | Status | Attempts |")
    lines.append("|---|---:|---:|---|---:|")
    for r in sorted(results, key=lambda item: item.latency_ms, reverse=True)[:8]:
        lines.append(f"| {r.case_id} | {str(r.ok).lower()} | {r.latency_ms:.1f} | {r.status} | {r.attempts} |")
    lines.append("")
    lines.append("## Per Case")
    lines.append("")
    lines.append("| Case | OK | App | Status | Attempts | Retry | Timeout | Fallback | Snapshot | Latency ms |")
    lines.append("|---|---:|---:|---|---:|---:|---:|---:|---:|---:|")
    for r in results:
        lines.append(
            f"| {r.case_id} | {str(r.ok).lower()} | {r.app_code} | {r.status} | {r.attempts} | {r.retry_count} | "
            f"{str(r.timeout).lower()} | {str(r.fallback_used).lower()} | {str(r.snapshot_restored).lower()} | {r.latency_ms:.1f} |"
        )
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/harness_eval_dataset.json")
    parser.add_argument("--output", default="")
    parser.add_argument("--timeout-sec", type=float, default=10.0)
    add_auth_args(parser)
    args = parser.parse_args()

    dataset_path = Path(args.dataset)
    cases = load_json(dataset_path)
    run_id = datetime.now().strftime("%Y%m%d%H%M%S")
    token = resolve_bearer_token(args, args.base_url, args.timeout_sec)
    results = [evaluate_case(args.base_url, case, args.timeout_sec, token, run_id) for case in cases]
    output = Path(args.output) if args.output else Path(f"harness_eval_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md")
    write_report(results, output, run_id)
    for result in results:
        status = "OK" if result.ok else "RISK"
        print(
            f"[{status}] {result.case_id} status={result.status} attempts={result.attempts} "
            f"retry={result.retry_count} timeout={result.timeout} fallback={result.fallback_used} "
            f"snapshot={result.snapshot_restored} latency={result.latency_ms:.1f} details={result.detail_failures}"
        )
    print(f"written to {output}")
    return 0 if all(r.ok for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
