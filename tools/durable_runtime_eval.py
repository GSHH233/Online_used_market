#!/usr/bin/env python3
"""Evaluate Durable Agent Runtime persistence, recovery, replay and permissions."""
from __future__ import annotations

import argparse
import json
import math
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
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
    def __init__(self, base_url: str, timeout: float, tokens: dict[str, str]):
        self.base = base_url.rstrip("/")
        self.timeout = timeout
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

    def start(self, fault_after: int = 0, question: str | None = None) -> dict[str, Any]:
        response, _ = self.request("POST", "/api/ai/agent/runs", "internal", {
            "question": question or "查询知识库中的二手手机无发票风险规则",
            "sessionId": f"runtime-eval-{time.time_ns()}",
            "maxSteps": 6,
            "failAfterNodeCount": fault_after,
        })
        require_code(response, 200)
        return response["data"]

    def get(self, run_id: str, auth: str = "internal") -> dict[str, Any]:
        response, _ = self.request("GET", f"/api/ai/agent/runs/{run_id}", auth)
        require_code(response, 200)
        return response["data"]

    def wait(self, run_id: str, statuses: set[str], timeout: float = 60) -> dict[str, Any]:
        deadline = time.time() + timeout
        latest: dict[str, Any] = {}
        while time.time() < deadline:
            latest = self.get(run_id)
            if latest.get("status") in statuses:
                return latest
            time.sleep(0.25)
        raise AssertionError(f"runTimeout:{run_id},lastStatus={latest.get('status')}")


def require_code(response: dict[str, Any], expected: int) -> None:
    actual = int(response.get("code") or 0)
    if actual != expected:
        raise AssertionError(f"appCode:{actual}!={expected},response={response}")


def tool_attempts(run: dict[str, Any]) -> dict[str, int]:
    return {str(node.get("nodeId")): int(node.get("attemptCount") or 0)
            for node in run.get("nodes") or [] if node.get("nodeType") == "TOOL"}


def evaluate(case: dict[str, Any], ctx: Context) -> Result:
    scenario = str(case["scenario"])
    failures: list[str] = []
    started = time.perf_counter()
    try:
        if scenario == "start_normal":
            run = ctx.start()
            ctx.values["normal"] = run["runId"]
            if run.get("status") not in {"CREATED", "PLANNING", "RUNNING", "COMPLETED"}:
                failures.append(f"invalidInitialStatus:{run.get('status')}")
        elif scenario == "normal_completed":
            run = ctx.wait(ctx.values["normal"], {"COMPLETED", "FAILED"})
            if run.get("status") != "COMPLETED": failures.append(f"status={run.get('status')}")
            if not run.get("answer"): failures.append("missingAnswer")
        elif scenario == "checkpoint_complete":
            response, _ = ctx.request("GET", f"/api/ai/agent/runs/{ctx.values['normal']}/checkpoints", "internal")
            require_code(response, 200)
            items = response.get("data") or []
            if not items: failures.append("missingCheckpoints")
            if not any(item.get("reason") == "COMPLETED" for item in items): failures.append("missingCompletedCheckpoint")
            if any(not item.get("stateHash") for item in items): failures.append("missingStateHash")
        elif scenario == "list_contains_run":
            response, _ = ctx.request("GET", "/api/ai/agent/runs?limit=30", "internal")
            require_code(response, 200)
            if ctx.values["normal"] not in {item.get("runId") for item in response.get("data") or []}:
                failures.append("runMissingFromList")
        elif scenario == "user_start_denied":
            response, _ = ctx.request("POST", "/api/ai/agent/runs", "user", {"question": "test"})
            require_code(response, 403)
        elif scenario == "user_detail_denied":
            response, _ = ctx.request("GET", f"/api/ai/agent/runs/{ctx.values['normal']}", "user")
            require_code(response, 403)
        elif scenario == "internal_cancel_denied":
            response, _ = ctx.request("POST", f"/api/ai/agent/runs/{ctx.values['normal']}/cancel", "internal")
            require_code(response, 403)
        elif scenario == "pause_running":
            run = ctx.start(question="查询 Prometheus P95、应用错误日志和数据库状态")
            ctx.values["paused"] = run["runId"]
            response, _ = ctx.request("POST", f"/api/ai/agent/runs/{run['runId']}/pause", "admin")
            if int(response.get("code") or 0) == 409:
                ctx.wait(run["runId"], {"COMPLETED", "FAILED"})
                ctx.values["pause_raced"] = True
            else:
                require_code(response, 200)
                if response["data"].get("status") != "PAUSED": failures.append("notPaused")
        elif scenario == "resume_paused":
            if not ctx.values.get("pause_raced"):
                response, _ = ctx.request("POST", f"/api/ai/agent/runs/{ctx.values['paused']}/resume", "admin")
                require_code(response, 200)
                if ctx.wait(ctx.values["paused"], {"COMPLETED", "FAILED"}).get("status") != "COMPLETED":
                    failures.append("resumePausedFailed")
        elif scenario == "fault_after_node":
            run = ctx.start(fault_after=1)
            failed = ctx.wait(run["runId"], {"FAILED"})
            ctx.values["fault"] = run["runId"]
            ctx.values["fault_attempts"] = tool_attempts(failed)
            if not any(node.get("status") == "SUCCEEDED" for node in failed.get("nodes") or []):
                failures.append("noSuccessfulNodeBeforeFailure")
        elif scenario == "concurrent_resume_without_duplicate":
            path = f"/api/ai/agent/runs/{ctx.values['fault']}/resume"
            with ThreadPoolExecutor(max_workers=2) as pool:
                responses = list(pool.map(lambda _: ctx.request("POST", path, "admin")[0], range(2)))
            codes = [int(response.get("code") or 0) for response in responses]
            if codes.count(200) != 1: failures.append(f"leaseWinnerCount:{codes.count(200)},codes={codes}")
            if any(code not in {200, 409} for code in codes): failures.append(f"unexpectedResumeCodes:{codes}")
            run = ctx.wait(ctx.values["fault"], {"COMPLETED", "FAILED"})
            if run.get("status") != "COMPLETED": failures.append("resumeNotCompleted")
            for node_id, before in ctx.values["fault_attempts"].items():
                after = tool_attempts(run).get(node_id)
                if before > 0 and after != before: failures.append(f"duplicateAttempt:{node_id}:{before}->{after}")
            ctx.values["source"] = run["runId"]
        elif scenario == "snapshot_replay":
            response, _ = ctx.request("POST", f"/api/ai/agent/runs/{ctx.values['source']}/replay?liveReadonly=false", "admin")
            require_code(response, 200)
            ctx.values["snapshot"] = response["data"]["runId"]
            if ctx.wait(ctx.values["snapshot"], {"COMPLETED", "FAILED"}).get("status") != "COMPLETED":
                failures.append("snapshotReplayFailed")
        elif scenario == "snapshot_replay_no_tool_calls":
            attempts = tool_attempts(ctx.get(ctx.values["snapshot"]))
            if any(value != 0 for value in attempts.values()): failures.append(f"snapshotCalledTools:{attempts}")
        elif scenario == "live_readonly_replay":
            response, _ = ctx.request("POST", f"/api/ai/agent/runs/{ctx.values['source']}/replay?liveReadonly=true", "admin")
            require_code(response, 200)
            ctx.values["live"] = response["data"]["runId"]
            if ctx.wait(ctx.values["live"], {"COMPLETED", "FAILED"}).get("status") != "COMPLETED":
                failures.append("liveReplayFailed")
        elif scenario == "live_replay_calls_tools":
            if not any(value > 0 for value in tool_attempts(ctx.get(ctx.values["live"])).values()):
                failures.append("liveReplayDidNotCallTools")
        else:
            failures.append(f"unknownScenario:{scenario}")
    except Exception as exc:
        failures.append(str(exc))
    latency = (time.perf_counter() - started) * 1000
    return Result(str(case["caseId"]), list(case.get("requirementIds") or []), scenario,
                  not failures, latency, failures)


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    if not ordered: return 0.0
    position = (len(ordered) - 1) * p / 100
    lower, upper = math.floor(position), math.ceil(position)
    if lower == upper: return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/durable_runtime_eval_dataset.json")
    parser.add_argument("--output", default="")
    parser.add_argument("--timeout-sec", type=float, default=60)
    parser.add_argument("--internal-bearer-token", required=True)
    parser.add_argument("--admin-bearer-token", required=True)
    parser.add_argument("--user-bearer-token", required=True)
    args = parser.parse_args()
    cases = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    context = Context(args.base_url, args.timeout_sec, {
        "internal": strip_bearer_prefix(args.internal_bearer_token),
        "admin": strip_bearer_prefix(args.admin_bearer_token),
        "user": strip_bearer_prefix(args.user_bearer_token),
    })
    results = [evaluate(case, context) for case in cases]
    for result in results:
        print(f"[{'OK' if result.ok else 'RISK'}] {result.case_id} latency={result.latency_ms:.1f} details={result.details}")
    passed = sum(result.ok for result in results)
    output = Path(args.output) if args.output else Path(
        f"durable_runtime_eval_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md")
    duplicate_count = sum("duplicateAttempt" in detail for result in results for detail in result.details)
    lines = ["# Durable Runtime Eval Report", "", f"- Cases: {len(results)}", f"- Passed: {passed}",
             f"- Pass rate: {passed / len(results):.2%}",
             f"- Resume success rate: {int(any(r.scenario == 'concurrent_resume_without_duplicate' and r.ok for r in results)):.2%}",
             f"- Duplicate side-effect count: {duplicate_count}",
             f"- P95 case latency: {percentile([r.latency_ms for r in results], 95):.1f} ms", "",
             "| Case | Requirements | Scenario | Status | Latency(ms) | Details |",
             "| --- | --- | --- | --- | ---: | --- |"]
    lines += [f"| {r.case_id} | {','.join(r.requirements)} | {r.scenario} | {'PASS' if r.ok else 'FAIL'} | {r.latency_ms:.1f} | {'; '.join(r.details)} |" for r in results]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"written to {output}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
