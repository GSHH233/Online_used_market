#!/usr/bin/env python3
"""Evaluate approval-gated Repair/Runbook operations and legacy endpoint closure."""
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
class Result:
    case_id: str
    requirements: list[str]
    scenario: str
    ok: bool
    latency_ms: float
    details: list[str]


class Context:
    def __init__(self, base: str, timeout: float, doc_id: int, tokens: dict[str, str]):
        self.base = base.rstrip("/")
        self.timeout = timeout
        self.doc_id = doc_id
        self.tokens = tokens
        self.values: dict[str, Any] = {}

    def request(self, method: str, path: str, auth: str,
                body: dict[str, Any] | None = None) -> tuple[dict[str, Any], float]:
        headers = {"Authorization": f"Bearer {self.tokens.get(auth, '')}"}
        payload = None
        if body is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=payload, headers=headers, method=method)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8")
        return json.loads(raw), (time.perf_counter() - started) * 1000

    def plan(self, auth: str = "internal", action: str = "REPAIR_KNOWLEDGE_DOC") -> dict[str, Any]:
        params = {"docId": self.doc_id, "forceReindex": False} if action == "REPAIR_KNOWLEDGE_DOC" else {
            "sampleDocs": 5, "perDocLimit": 50, "forceReindex": False}
        response, _ = self.request("POST", "/api/ai/operations/plans", auth, {
            "actionCode": action, "reason": "controlled operation eval", "params": params})
        require(response, 200)
        return response["data"]


def require(response: dict[str, Any], expected: int) -> None:
    actual = int(response.get("code") or 0)
    if actual != expected:
        raise AssertionError(f"appCode:{actual}!={expected},response={response}")


def evaluate(case: dict[str, Any], ctx: Context) -> Result:
    scenario = str(case["scenario"])
    failures: list[str] = []
    started = time.perf_counter()
    try:
        if scenario == "create_plan":
            plan = ctx.plan()
            ctx.values["plan"] = plan
            if plan.get("status") != "PENDING_APPROVAL": failures.append("notPendingApproval")
            if plan.get("riskLevel") != "MEDIUM": failures.append("wrongRiskLevel")
        elif scenario == "execute_before_approval_denied":
            plan = ctx.values["plan"]
            response, _ = ctx.request("POST", f"/api/ai/operations/{plan['operationId']}/execute", "admin")
            require(response, 409)
        elif scenario == "internal_approve_denied":
            plan = ctx.values["plan"]
            response, _ = ctx.request("POST", f"/api/ai/operations/{plan['operationId']}/approve", "internal", {"reason": "deny"})
            require(response, 403)
        elif scenario == "admin_approve":
            plan = ctx.values["plan"]
            response, _ = ctx.request("POST", f"/api/ai/operations/{plan['operationId']}/approve", "admin", {"reason": "approved by eval"})
            require(response, 200)
            if response["data"].get("status") != "APPROVED": failures.append("notApproved")
        elif scenario == "execute_approved_snapshot":
            plan = ctx.values["plan"]
            response, _ = ctx.request("POST", f"/api/ai/operations/{plan['operationId']}/execute", "admin")
            require(response, 200)
            data = response["data"]
            if not data.get("execution"): failures.append("missingExecution")
            elif data["execution"].get("verificationStatus") != "PASSED": failures.append("verificationNotPassed")
        elif scenario == "snapshot_params_unchanged":
            plan = ctx.values["plan"]
            response, _ = ctx.request("GET", f"/api/ai/operations/{plan['operationId']}", "internal")
            require(response, 200)
            params = response["data"].get("params") or {}
            if int(params.get("docId") or -1) != ctx.doc_id: failures.append("approvedDocIdChanged")
            if params.get("forceReindex") is not False: failures.append("approvedForceFlagChanged")
        elif scenario == "admin_reject":
            plan = ctx.plan()
            ctx.values["rejected"] = plan
            response, _ = ctx.request("POST", f"/api/ai/operations/{plan['operationId']}/reject", "admin", {"reason": "unsafe"})
            require(response, 200)
            if response["data"].get("status") != "REJECTED": failures.append("notRejected")
        elif scenario == "execute_rejected_denied":
            plan = ctx.values["rejected"]
            response, _ = ctx.request("POST", f"/api/ai/operations/{plan['operationId']}/execute", "admin")
            require(response, 409)
        elif scenario == "legacy_repair_blocked":
            response, _ = ctx.request("POST", f"/api/ai/knowledge/repair/{ctx.doc_id}?forceReindex=false&async=false", "admin")
            require(response, 409)
        elif scenario == "legacy_runbook_blocked":
            response, _ = ctx.request("POST", "/api/ai/aiops/runbooks/execute", "admin", {
                "actionCode": "REPAIR_KNOWLEDGE_DOC", "dryRun": False, "params": {"docId": ctx.doc_id}})
            require(response, 409)
        elif scenario == "user_create_denied":
            response, _ = ctx.request("POST", "/api/ai/operations/plans", "user", {
                "actionCode": "REPAIR_KNOWLEDGE_DOC", "params": {"docId": ctx.doc_id}})
            require(response, 403)
        elif scenario == "high_risk_batch_manual_only":
            plan = ctx.plan(action="REPAIR_KNOWLEDGE_BATCH")
            if plan.get("riskLevel") != "HIGH": failures.append("batchRiskNotHigh")
            response, _ = ctx.request("POST", f"/api/ai/operations/{plan['operationId']}/approve", "admin", {"reason": "eval"})
            require(response, 200)
            response, _ = ctx.request("POST", f"/api/ai/operations/{plan['operationId']}/execute", "admin")
            require(response, 403)
        else:
            failures.append(f"unknownScenario:{scenario}")
    except Exception as exc:
        failures.append(str(exc))
    latency = (time.perf_counter() - started) * 1000
    maximum = float(case.get("maxLatencyMs") or 5000)
    if latency > maximum: failures.append(f"latencyExceeded:{latency:.1f}>{maximum:.1f}")
    return Result(str(case["caseId"]), list(case.get("requirementIds") or []), scenario,
                  not failures, latency, failures)


def percentile(values: list[float], p: float) -> float:
    values = sorted(values)
    if not values: return 0.0
    pos = (len(values) - 1) * p / 100
    low, high = math.floor(pos), math.ceil(pos)
    return values[low] if low == high else values[low] + (values[high] - values[low]) * (pos - low)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/controlled_operation_eval_dataset.json")
    parser.add_argument("--output", default="")
    parser.add_argument("--doc-id", type=int, default=19)
    parser.add_argument("--timeout-sec", type=float, default=30)
    parser.add_argument("--internal-bearer-token", required=True)
    parser.add_argument("--admin-bearer-token", required=True)
    parser.add_argument("--user-bearer-token", required=True)
    args = parser.parse_args()
    cases = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    context = Context(args.base_url, args.timeout_sec, args.doc_id, {
        "internal": strip_bearer_prefix(args.internal_bearer_token),
        "admin": strip_bearer_prefix(args.admin_bearer_token),
        "user": strip_bearer_prefix(args.user_bearer_token),
    })
    results = [evaluate(case, context) for case in cases]
    for result in results:
        print(f"[{'OK' if result.ok else 'RISK'}] {result.case_id} latency={result.latency_ms:.1f} details={result.details}")
    output = Path(args.output) if args.output else Path(
        f"controlled_operation_eval_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md")
    passed = sum(result.ok for result in results)
    lines = ["# Controlled Operation Eval Report", "", f"- Cases: {len(results)}", f"- Passed: {passed}",
             f"- Pass rate: {passed / len(results):.2%}",
             f"- P95 latency: {percentile([r.latency_ms for r in results], 95):.1f} ms", "",
             "| Case | Requirements | Scenario | Status | Latency(ms) | Details |",
             "| --- | --- | --- | --- | ---: | --- |"]
    lines += [f"| {r.case_id} | {','.join(r.requirements)} | {r.scenario} | {'PASS' if r.ok else 'FAIL'} | {r.latency_ms:.1f} | {'; '.join(r.details)} |" for r in results]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"written to {output}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
