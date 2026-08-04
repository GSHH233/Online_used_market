#!/usr/bin/env python3
"""Evaluate AIOps approval, remediation, escalation, idempotency, and MTTR."""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from eval_auth import add_auth_args, resolve_bearer_token, strip_bearer_prefix


@dataclass
class CaseResult:
    case_id: str
    requirement_ids: list[str]
    scenario: str
    ok: bool
    latency_ms: float
    details: list[str]


class EvalContext:
    def __init__(self, base_url: str, timeout_sec: float, tokens: dict[str, str], run_id: str, doc_id: int):
        self.base_url = base_url.rstrip("/")
        self.timeout_sec = timeout_sec
        self.tokens = tokens
        self.run_id = run_id
        self.doc_id = doc_id
        self.values: dict[str, Any] = {}
        self.counter = 0

    def request(self, method: str, path: str, auth: str,
                body: dict[str, Any] | None = None) -> tuple[dict[str, Any], float]:
        token = self.tokens.get(auth, "")
        if not token:
            raise RuntimeError(f"missing {auth} token")
        headers = {"Authorization": f"Bearer {token}"}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_sec) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8")
        latency_ms = (time.perf_counter() - started) * 1000
        return json.loads(raw), latency_ms

    def create_event(self, suffix: str, source: str | None = None) -> int:
        self.counter += 1
        body = {
            "eventKey": f"aiops-loop-{self.run_id}-{self.counter}-{suffix}",
            "severity": "WARN",
            "source": source or f"aiops-loop-eval-{self.run_id}",
            "title": f"AIOps loop eval {suffix}",
            "summary": "created by aiops remediation loop eval",
            "metadata": {"runId": self.run_id, "scenario": suffix},
        }
        response, _ = self.request("POST", "/api/ai/aiops/events", "admin", body)
        require_code(response, 200)
        return int(response["data"]["id"])

    def approve(self, event_id: int, auth: str = "admin") -> dict[str, Any]:
        response, _ = self.request(
            "POST", f"/api/ai/aiops/events/{event_id}/approve", auth, {"reason": "approved by eval"}
        )
        return response

    def create_operation(self, action_code: str, params: dict[str, Any],
                         reason: str) -> dict[str, Any]:
        self.counter += 1
        response, _ = self.request(
            "POST",
            "/api/ai/operations/plans",
            "admin",
            {
                "actionCode": action_code,
                "params": params,
                "reason": reason,
                "traceId": f"aiops-loop-{self.run_id}-{self.counter}",
            },
        )
        require_code(response, 200)
        return response["data"]

    def approve_operation(self, operation_id: int, auth: str = "admin") -> dict[str, Any]:
        response, _ = self.request(
            "POST",
            f"/api/ai/operations/{operation_id}/approve",
            auth,
            {"reason": "approved by remediation loop eval"},
        )
        return response

    def execute_operation(self, operation_id: int,
                          auth: str = "admin") -> tuple[dict[str, Any], float]:
        return self.request("POST", f"/api/ai/operations/{operation_id}/execute", auth)

    def operation(self, operation_id: int) -> dict[str, Any]:
        response, _ = self.request("GET", f"/api/ai/operations/{operation_id}", "internal")
        require_code(response, 200)
        return response["data"]

    def event(self, event_id: int) -> dict[str, Any]:
        response, _ = self.request("GET", f"/api/ai/aiops/events/{event_id}", "internal")
        require_code(response, 200)
        return response["data"]

    def ensure_mttr_fixture(self) -> dict[str, Any]:
        existing = self.values.get("mttr")
        if isinstance(existing, dict):
            return existing
        durations: list[float] = []
        fixture_events: list[dict[str, Any]] = []
        for index, sleep_ms in enumerate((30, 70, 120), start=1):
            operation = self.create_operation(
                "MARK_EVENT_RESOLVED",
                {"summary": f"resolved for MTTR eval {index}"},
                f"MTTR fixture {self.run_id}-{index}",
            )
            operation_id = int(operation["operationId"])
            require_code(self.approve_operation(operation_id), 200)
            time.sleep(sleep_ms / 1000)
            response, _ = self.execute_operation(operation_id)
            require_code(response, 200)
            event = self.event(operation_id)
            fixture_events.append(event)
            durations.append(duration_ms(event["firstSeenAt"], event["resolvedAt"]))
        window_start = min(parse_datetime(event["firstSeenAt"]) for event in fixture_events)
        window_end = max(parse_datetime(event["resolvedAt"]) for event in fixture_events)
        query = urllib.parse.urlencode(
            {
                "source": "controlled_operation",
                "from": (window_start - timedelta(microseconds=1)).isoformat(timespec="microseconds"),
                "to": (window_end + timedelta(microseconds=1)).isoformat(timespec="microseconds"),
            }
        )
        metrics_response, latency = self.request(
            "GET", f"/api/ai/aiops/events/metrics/mttr?{query}", "internal"
        )
        require_code(metrics_response, 200)
        fixture = {"durations": durations, "metrics": metrics_response["data"], "latency": latency}
        self.values["mttr"] = fixture
        return fixture


def require_code(response: dict[str, Any], expected: int) -> None:
    actual = int(response.get("code") or 0)
    if actual != expected:
        raise AssertionError(f"appCode:{actual}!={expected},response={response}")


def expect(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def run_case(case: dict[str, Any], ctx: EvalContext) -> CaseResult:
    case_id = str(case.get("caseId") or "")
    scenario = str(case.get("scenario") or "")
    requirement_ids = [str(x) for x in case.get("requirementIds") or []]
    failures: list[str] = []
    started = time.perf_counter()
    try:
        if scenario == "admin_approve":
            response = ctx.approve(ctx.create_event(scenario))
            require_code(response, 200)
            expect(response["data"].get("status") == "APPROVED", "statusNotApproved", failures)

        elif scenario in ("internal_approve_denied", "user_approve_denied"):
            auth = "internal" if scenario.startswith("internal") else "user"
            response = ctx.approve(ctx.create_event(scenario), auth)
            require_code(response, 403)

        elif scenario == "admin_reject":
            event_id = ctx.create_event(scenario)
            response, _ = ctx.request(
                "POST", f"/api/ai/aiops/events/{event_id}/reject", "admin", {"reason": "unsafe plan"}
            )
            require_code(response, 200)
            expect(response["data"].get("status") == "REJECTED", "statusNotRejected", failures)

        elif scenario == "internal_reject_denied":
            event_id = ctx.create_event(scenario)
            response, _ = ctx.request(
                "POST", f"/api/ai/aiops/events/{event_id}/reject", "internal", {"reason": "deny"}
            )
            require_code(response, 403)

        elif scenario == "direct_resolved_denied":
            event_id = ctx.create_event(scenario)
            response, _ = ctx.request(
                "PATCH", f"/api/ai/aiops/events/{event_id}", "internal", {"status": "RESOLVED"}
            )
            require_code(response, 403)

        elif scenario == "unapproved_execute_conflict":
            operation = ctx.create_operation(
                "MARK_EVENT_RESOLVED",
                {"summary": "should not run"},
                "unapproved operation must not execute",
            )
            response, _ = ctx.execute_operation(int(operation["operationId"]))
            require_code(response, 409)

        elif scenario == "internal_dry_run":
            response, _ = ctx.request(
                "POST", "/api/ai/aiops/runbooks/execute", "internal",
                {"actionCode": "REPAIR_KNOWLEDGE_DOC", "dryRun": True, "params": {"docId": ctx.doc_id}},
            )
            require_code(response, 200)
            expect(response["data"].get("status") == "DRY_RUN", "dryRunStatus", failures)

        elif scenario == "repair_and_verify":
            operation = ctx.create_operation(
                "REPAIR_KNOWLEDGE_DOC",
                {"docId": ctx.doc_id, "forceReindex": False},
                "repair and verify by remediation loop eval",
            )
            operation_id = int(operation["operationId"])
            require_code(ctx.approve_operation(operation_id), 200)
            response, _ = ctx.execute_operation(operation_id)
            require_code(response, 200)
            data = response["data"]
            execution = data.get("execution") or {}
            expect(execution.get("status") == "SUCCESS", "repairNotSuccess", failures)
            expect(execution.get("verificationStatus") == "PASSED", "verificationNotPassed", failures)
            event = ctx.event(operation_id)
            expect(event.get("status") == "RESOLVED", "eventNotResolved", failures)
            expect(bool(event.get("resolvedAt")), "missingResolvedAt", failures)
            ctx.values["repair"] = {
                "operationId": operation_id,
                "actionId": execution.get("actionId"),
            }

        elif scenario == "idempotent_replay":
            saved = ctx.values.get("repair") or {}
            response, _ = ctx.execute_operation(int(saved["operationId"]))
            require_code(response, 200)
            execution = response["data"].get("execution") or {}
            expect(execution.get("status") == "SKIPPED", "notSkipped", failures)
            expect(execution.get("verificationStatus") == "IDEMPOTENT_HIT", "notIdempotentHit", failures)
            expect(execution.get("actionId") == saved.get("actionId"), "actionIdChanged", failures)

        elif scenario == "execution_failure_escalates":
            operation = ctx.create_operation(
                "REPAIR_KNOWLEDGE_DOC",
                {"docId": 999999999, "forceReindex": True},
                "invalid document must escalate",
            )
            operation_id = int(operation["operationId"])
            require_code(ctx.approve_operation(operation_id), 200)
            response, _ = ctx.execute_operation(operation_id)
            require_code(response, 200)
            execution = response["data"].get("execution") or {}
            expect(execution.get("status") == "FAILED", "actionNotFailed", failures)
            event = ctx.event(operation_id)
            expect(event.get("status") == "ESCALATED", "eventNotEscalated", failures)
            ctx.values["escalatedEventId"] = operation_id

        elif scenario == "escalation_audit_fields":
            event = ctx.event(int(ctx.values["escalatedEventId"]))
            expect(event.get("manualRequired") is True, "manualRequiredFalse", failures)
            expect(bool(event.get("escalationReason")), "missingEscalationReason", failures)
            failed = [x for x in event.get("actions") or [] if x.get("status") == "FAILED"]
            expect(bool(failed), "missingFailedAction", failures)
            if failed:
                expect(bool(failed[-1].get("traceId")), "missingActionTraceId", failures)
                expect(failed[-1].get("verificationStatus") == "FAILED", "missingFailedVerification", failures)

        elif scenario == "manual_override":
            operation = ctx.create_operation(
                "MARK_EVENT_RESOLVED",
                {"summary": "manual override eval"},
                "manual override by remediation loop eval",
            )
            operation_id = int(operation["operationId"])
            require_code(ctx.approve_operation(operation_id), 200)
            response, _ = ctx.execute_operation(operation_id)
            require_code(response, 200)
            event = ctx.event(operation_id)
            expect(event.get("status") == "RESOLVED", "manualNotResolved", failures)
            expect(event.get("resolutionType") == "MANUAL_OVERRIDE", "resolutionTypeWrong", failures)

        elif scenario.startswith("mttr_"):
            fixture = ctx.ensure_mttr_fixture()
            durations = fixture["durations"]
            metrics = fixture["metrics"]
            expected = {
                "mttr_average": statistics.mean(durations),
                "mttr_p50": percentile(durations, 50),
                "mttr_p95": percentile(durations, 95),
            }[scenario]
            key = {
                "mttr_average": "averageMttrMs",
                "mttr_p50": "p50MttrMs",
                "mttr_p95": "p95MttrMs",
            }[scenario]
            actual = float(metrics.get(key))
            expect(abs(actual - expected) <= 1.0, f"{key}:{actual}!~={expected}", failures)
            expect(int(metrics.get("resolvedCount") or 0) == 3, "resolvedCountNot3", failures)

        else:
            failures.append(f"unknownScenario:{scenario}")
    except Exception as exc:
        failures.append(f"error:{exc}")

    latency_ms = (time.perf_counter() - started) * 1000
    max_latency = float(case.get("maxLatencyMs") or 5000)
    if latency_ms > max_latency:
        failures.append(f"latencyExceeded:{latency_ms:.1f}>{max_latency:.1f}")
    return CaseResult(case_id, requirement_ids, scenario, not failures, latency_ms, failures)


def parse_datetime(value: Any) -> datetime:
    return datetime.fromisoformat(str(value).replace("Z", "+00:00"))


def duration_ms(start: Any, end: Any) -> float:
    return (parse_datetime(end) - parse_datetime(start)).total_seconds() * 1000


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    position = (len(ordered) - 1) * p / 100
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def write_report(results: list[CaseResult], output: Path, run_id: str) -> None:
    total = len(results)
    passed = sum(1 for result in results if result.ok)
    lines = [
        "# AIOps Remediation Loop Eval Report", "", f"- Run ID: `{run_id}`", f"- Cases: {total}",
        f"- Passed: {passed}",
        f"- Closure pass rate: {passed / total:.2%}" if total else "- Closure pass rate: 0.00%",
        f"- P95 latency ms: {percentile([x.latency_ms for x in results], 95):.1f}", "",
        "| Case | Requirements | Scenario | OK | Latency ms | Details |",
        "|---|---|---|---:|---:|---|",
    ]
    for result in results:
        lines.append(
            f"| {result.case_id} | {','.join(result.requirement_ids)} | {result.scenario} | "
            f"{str(result.ok).lower()} | {result.latency_ms:.1f} | `{','.join(result.details)}` |"
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/aiops_remediation_loop_eval_dataset.json")
    parser.add_argument("--output", default="")
    parser.add_argument("--timeout-sec", type=float, default=20.0)
    parser.add_argument("--doc-id", type=int, default=19)
    parser.add_argument("--admin-bearer-token", default="")
    parser.add_argument("--internal-bearer-token", default="")
    parser.add_argument("--user-bearer-token", default="")
    add_auth_args(parser)
    args = parser.parse_args()

    admin_token = strip_bearer_prefix(
        args.admin_bearer_token or resolve_bearer_token(args, args.base_url, args.timeout_sec)
    )
    tokens = {
        "admin": admin_token,
        "internal": strip_bearer_prefix(args.internal_bearer_token),
        "user": strip_bearer_prefix(args.user_bearer_token),
    }
    cases = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    run_id = datetime.now().strftime("%Y%m%d%H%M%S")
    context = EvalContext(args.base_url, args.timeout_sec, tokens, run_id, args.doc_id)
    results = [run_case(case, context) for case in cases]
    output = Path(args.output) if args.output else Path(
        f"aiops_remediation_loop_eval_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md"
    )
    write_report(results, output, run_id)
    for result in results:
        print(
            f"[{'OK' if result.ok else 'RISK'}] {result.case_id} scenario={result.scenario} "
            f"latency={result.latency_ms:.1f} details={result.details}"
        )
    print(f"written to {output}")
    return 0 if all(result.ok for result in results) else 1


if __name__ == "__main__":
    sys.exit(main())
