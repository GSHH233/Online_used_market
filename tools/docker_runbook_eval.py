#!/usr/bin/env python3
"""Evaluate the isolated Docker restart/health/rollback controlled operation."""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from eval_auth import strip_bearer_prefix


ACTION = "DOCKER_RESTART_VERIFY_ROLLBACK"


@dataclass
class Result:
    case_id: str
    scenario: str
    status: str
    latency_ms: float
    details: list[str]


def request_json(base: str, method: str, path: str, token: str,
                 timeout: float, body: dict[str, Any] | None = None) -> tuple[dict[str, Any], float]:
    headers = {"Authorization": "Bearer " + strip_bearer_prefix(token)} if token else {}
    payload = None
    if body is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(base.rstrip("/") + path, data=payload,
                                     headers=headers, method=method)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        if not raw:
            raw = json.dumps({"code": exc.code, "message": str(exc), "data": None})
    return json.loads(raw), (time.perf_counter() - started) * 1000


def expect_code(response: dict[str, Any], expected: int) -> None:
    actual = int(response.get("code") or 0)
    if actual != expected:
        raise AssertionError(f"appCode:{actual}!={expected},response={response}")


def plan_body(target: str, extra: dict[str, Any] | None = None) -> dict[str, Any]:
    params: dict[str, Any] = {"targetId": target}
    if extra:
        params.update(extra)
    return {
        "actionCode": ACTION,
        "reason": "isolated Docker runbook evaluation",
        "traceId": "docker-eval-" + datetime.now().strftime("%Y%m%d%H%M%S%f"),
        "params": params,
    }


def evaluate(case: dict[str, Any], args: argparse.Namespace, state: dict[str, Any]) -> Result:
    scenario = str(case["scenario"])
    details: list[str] = []
    started = time.perf_counter()
    try:
        if scenario == "catalog":
            response, _ = request_json(args.base_url, "GET", "/api/ai/aiops/runbooks",
                                       args.internal_bearer_token, args.timeout_sec)
            expect_code(response, 200)
            actions = response.get("data") or []
            item = next((value for value in actions if value.get("actionCode") == ACTION), None)
            if not item:
                details.append("actionMissing")
            else:
                metadata = item.get("metadata") or {}
                if bool(metadata.get("enabled")) != bool(args.expect_enabled):
                    details.append(f"enabled:{metadata.get('enabled')}!={args.expect_enabled}")
                if metadata.get("targetId") != args.target_id:
                    details.append("targetIdMismatch")

        elif scenario == "user_catalog_denied":
            response, _ = request_json(args.base_url, "GET", "/api/ai/aiops/runbooks",
                                       args.user_bearer_token, args.timeout_sec)
            expect_code(response, 403)

        elif scenario == "infrastructure_params_rejected":
            response, _ = request_json(args.base_url, "POST", "/api/ai/operations/plans",
                                       args.internal_bearer_token, args.timeout_sec,
                                       plan_body(args.target_id, {"containerName": "postgres"}))
            expect_code(response, 400)

        elif scenario == "unknown_target_rejected":
            response, _ = request_json(args.base_url, "POST", "/api/ai/operations/plans",
                                       args.internal_bearer_token, args.timeout_sec,
                                       plan_body("not-allowlisted"))
            expect_code(response, 403 if args.expect_enabled else 409)

        elif scenario == "dry_run":
            response, _ = request_json(args.base_url, "POST", "/api/ai/aiops/runbooks/execute",
                                       args.internal_bearer_token, args.timeout_sec, {
                                           "actionCode": ACTION,
                                           "dryRun": True,
                                           "params": {"targetId": args.target_id},
                                       })
            if args.expect_enabled:
                expect_code(response, 200)
                if (response.get("data") or {}).get("status") != "DRY_RUN":
                    details.append("dryRunStatusMissing")
            else:
                expect_code(response, 409)

        elif scenario == "execute_before_approval_denied":
            response, _ = request_json(args.base_url, "POST", "/api/ai/operations/plans",
                                       args.internal_bearer_token, args.timeout_sec,
                                       plan_body(args.target_id))
            expect_code(response, 200)
            operation_id = int((response.get("data") or {}).get("operationId") or 0)
            state["operation_id"] = operation_id
            response, _ = request_json(args.base_url, "POST",
                                       f"/api/ai/operations/{operation_id}/execute",
                                       args.admin_bearer_token, args.timeout_sec)
            expect_code(response, 409)

        elif scenario == "approve_and_execute":
            operation_id = int(state.get("operation_id") or 0)
            if not operation_id:
                raise AssertionError("missingOperationId")
            response, _ = request_json(args.base_url, "POST",
                                       f"/api/ai/operations/{operation_id}/approve",
                                       args.admin_bearer_token, args.timeout_sec,
                                       {"reason": "isolated live evaluation"})
            expect_code(response, 200)
            response, _ = request_json(args.base_url, "POST",
                                       f"/api/ai/operations/{operation_id}/execute",
                                       args.admin_bearer_token, args.timeout_sec)
            expect_code(response, 200)
            operation = response.get("data") or {}
            execution = operation.get("execution") or {}
            if execution.get("status") != "SUCCESS":
                details.append(f"status:{execution.get('status')}")
            if execution.get("verificationStatus") not in {"PASSED", "ROLLED_BACK"}:
                details.append(f"verification:{execution.get('verificationStatus')}")
            result = execution.get("result") or {}
            stages = result.get("stages") or []
            stage_names = {value.get("stage") for value in stages if isinstance(value, dict)}
            if not {"POLICY", "RESTART", "HEALTH_CHECK"}.issubset(stage_names):
                details.append("requiredStagesMissing")

        else:
            raise AssertionError("unknownScenario:" + scenario)
    except Exception as exc:  # noqa: BLE001 - evaluation must report every case
        details.append("error:" + str(exc))

    latency = (time.perf_counter() - started) * 1000
    if latency > float(case.get("maxLatencyMs") or 999999):
        details.append(f"latencyExceeded:{latency:.1f}")
    return Result(str(case["caseId"]), scenario, "PASS" if not details else "RISK", latency, details)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", type=Path,
                        default=Path("eval/datasets/docker_runbook_eval_dataset.json"))
    parser.add_argument("--output", type=Path,
                        default=Path("eval/reports/docker_runbook_eval_report.md"))
    parser.add_argument("--target-id", default="marketplace-demo-web")
    parser.add_argument("--user-bearer-token", required=True)
    parser.add_argument("--internal-bearer-token", required=True)
    parser.add_argument("--admin-bearer-token", required=True)
    parser.add_argument("--expect-enabled", action="store_true")
    parser.add_argument("--live-execute", action="store_true")
    parser.add_argument("--timeout-sec", type=float, default=50.0)
    args = parser.parse_args()

    cases = json.loads(args.dataset.read_text(encoding="utf-8"))
    state: dict[str, Any] = {}
    results: list[Result] = []
    for case in cases:
        if case.get("liveOnly") and not args.live_execute:
            results.append(Result(case["caseId"], case["scenario"], "SKIP", 0.0,
                                  ["requires --live-execute"]))
            continue
        result = evaluate(case, args, state)
        results.append(result)
        print(f"[{result.status}] {result.case_id} latency={result.latency_ms:.1f} details={result.details}")

    passed = sum(result.status == "PASS" for result in results)
    risks = sum(result.status == "RISK" for result in results)
    skipped = sum(result.status == "SKIP" for result in results)
    lines = [
        "# Isolated Docker Runbook Eval Report", "",
        f"- Cases: {len(results)}", f"- Passed: {passed}",
        f"- Risk: {risks}", f"- Skipped: {skipped}",
        f"- Docker runbook expected enabled: {args.expect_enabled}",
        f"- Live execution: {args.live_execute}", "",
        "| Case | Scenario | Status | Latency(ms) | Details |",
        "|---|---|---|---:|---|",
    ]
    for result in results:
        lines.append(f"| {result.case_id} | {result.scenario} | {result.status} | "
                     f"{result.latency_ms:.1f} | `{json.dumps(result.details, ensure_ascii=False)}` |")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"written to {args.output}")
    return 1 if risks else 0


if __name__ == "__main__":
    raise SystemExit(main())
