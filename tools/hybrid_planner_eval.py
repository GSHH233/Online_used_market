#!/usr/bin/env python3
"""Evaluate hybrid planning, bounded replanning, permission stability, and DAG validity."""

from __future__ import annotations

import argparse
import json
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


@dataclass
class CaseResult:
    case_id: str
    ok: bool
    latency_ms: float
    planner: str
    replan_rounds: int
    tools: list[str]
    denied_tools: list[str]
    details: list[str]


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def post_json(url: str, payload: dict[str, Any], timeout_sec: float, token: str) -> tuple[dict[str, Any], float]:
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            body = json.loads(response.read().decode("utf-8"))
    finally:
        latency_ms = (time.perf_counter() - started) * 1000
    return body, latency_ms


def list_of_strings(value: Any) -> list[str]:
    return [str(item) for item in value] if isinstance(value, list) else []


def extract_trace(data: dict[str, Any]) -> tuple[list[str], list[str], str]:
    tools: list[str] = []
    denied: list[str] = []
    planner = ""
    for step in data.get("agentTrace") or []:
        if not isinstance(step, dict):
            continue
        tool = str(step.get("tool") or "")
        phase = str(step.get("phase") or "")
        status = str(step.get("status") or "").upper()
        metadata = step.get("metadata") if isinstance(step.get("metadata"), dict) else {}
        if tool and phase == "tool_call":
            tools.append(tool)
            if status == "DENIED":
                denied.append(tool)
        if phase in {"plan", "graph_plan"}:
            planner = str(metadata.get("planner") or "")
    return tools, denied, planner


def parse_replan_rounds(debug_info: Any) -> int:
    match = re.search(r"(?:^|,)replanRounds=(\d+)", str(debug_info or ""))
    return int(match.group(1)) if match else 0


def graph_errors(graph: Any) -> list[str]:
    if not isinstance(graph, list):
        return ["missingGraph"]
    ids: list[str] = [str(node.get("nodeId") or "") for node in graph if isinstance(node, dict)]
    errors: list[str] = []
    if len(ids) != len(set(ids)):
        errors.append("duplicateNodeIds")
    known: set[str] = set()
    for node in graph:
        if not isinstance(node, dict):
            continue
        node_id = str(node.get("nodeId") or "")
        for dependency in list_of_strings(node.get("dependsOn")):
            if dependency not in known:
                errors.append(f"invalidDependency:{node_id}->{dependency}")
        known.add(node_id)
    return errors


def evaluate(base_url: str, case: dict[str, Any], timeout_sec: float, token: str) -> CaseResult:
    case_id = str(case.get("caseId") or "UNKNOWN")
    engine = str(case.get("engine") or "react").lower()
    max_steps = int(case.get("maxSteps", 5))
    max_allowed_steps = 8 if engine == "graph" else 5
    if engine not in {"react", "graph"}:
        return CaseResult(
            case_id, False, 0.0, "", 0, [], [], [f"invalidFixture:engine={engine}"]
        )
    if not 1 <= max_steps <= max_allowed_steps:
        return CaseResult(
            case_id, False, 0.0, "", 0, [], [],
            [f"invalidFixture:maxSteps={max_steps},allowed=1..{max_allowed_steps}"]
        )
    endpoint = "/api/ai/agent/graph-chat" if engine == "graph" else "/api/ai/agent/react-chat"
    payload = {
        "question": case.get("question"),
        "sessionId": "hybrid-planner-eval",
        "traceId": f"hybrid-{case_id.lower()}",
        "mode": case.get("mode", "INTERNAL"),
        "maxSteps": max_steps,
    }
    details: list[str] = []
    latency_ms = 0.0
    try:
        response, latency_ms = post_json(base_url.rstrip("/") + endpoint, payload, timeout_sec, token)
        if response.get("code") != 200:
            raise RuntimeError(f"appCode={response.get('code')},message={response.get('message')}")
        data = response.get("data") if isinstance(response.get("data"), dict) else {}
        tools, denied, planner = extract_trace(data)
        replan_rounds = parse_replan_rounds(data.get("debugInfo"))

        expected = list_of_strings(case.get("expectedTools"))
        missing = [tool for tool in expected if tool not in tools]
        if missing:
            details.append("missingTools:" + ",".join(missing))
        expected_denied = list_of_strings(case.get("expectedDeniedTools"))
        missing_denied = [tool for tool in expected_denied if tool not in denied]
        if missing_denied:
            details.append("missingDeniedTools:" + ",".join(missing_denied))
        forbidden = [tool for tool in list_of_strings(case.get("forbidTools")) if tool in tools]
        if forbidden:
            details.append("forbiddenTools:" + ",".join(forbidden))
        if len(tools) != len(set(tools)):
            details.append("duplicateToolCalls")
        if len(tools) > max_steps:
            details.append(f"toolBudgetExceeded:{len(tools)}")

        expected_planner = str(case.get("expectedPlanner") or "")
        if expected_planner and planner != expected_planner:
            details.append(f"planner:{planner}!={expected_planner}")
        max_rounds = int(case.get("maxReplanRounds", 2))
        if replan_rounds > max_rounds:
            details.append(f"replanRounds:{replan_rounds}>{max_rounds}")
        if case.get("requireGraph"):
            details.extend(graph_errors(data.get("graph")))

        return CaseResult(case_id, not details, latency_ms, planner, replan_rounds, tools, denied, details)
    except (urllib.error.URLError, TimeoutError, ValueError, RuntimeError, KeyError) as error:
        details.append("error:" + str(error))
        return CaseResult(case_id, False, latency_ms, "", 0, [], [], details)


def percentile(values: list[float], percent: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((percent / 100) * (len(ordered) - 1)))))
    return ordered[index]


def report(results: list[CaseResult]) -> str:
    passed = sum(1 for result in results if result.ok)
    latencies = [result.latency_ms for result in results]
    lines = [
        "# Hybrid Planner Evaluation Report",
        "",
        f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"- Cases: {len(results)}",
        f"- Passed: {passed}",
        f"- Pass rate: {passed / len(results):.2%}" if results else "- Pass rate: 0.00%",
        f"- P50 latency ms: {statistics.median(latencies) if latencies else 0:.1f}",
        f"- P95 latency ms: {percentile(latencies, 95):.1f}",
        "",
        "| Case | OK | Planner | Replan rounds | Tools | Denied | Latency ms | Details |",
        "|---|---:|---|---:|---|---|---:|---|",
    ]
    for result in results:
        lines.append(
            f"| {result.case_id} | {str(result.ok).lower()} | {result.planner} | "
            f"{result.replan_rounds} | {result.tools} | {result.denied_tools} | "
            f"{result.latency_ms:.1f} | `{';'.join(result.details)[:240]}` |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate hybrid Planner and bounded dynamic Replan.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/hybrid_planner_eval_dataset.json")
    parser.add_argument("--timeout-sec", type=float, default=120)
    parser.add_argument("--out", default="eval/reports/hybrid_planner_eval_report.md")
    add_auth_args(parser)
    args = parser.parse_args()
    args.bearer_token = resolve_bearer_token(args, args.base_url, args.timeout_sec)

    cases = load_json(Path(args.dataset))
    if not isinstance(cases, list):
        print("Dataset must be a JSON array.", file=sys.stderr)
        return 2
    results = [evaluate(args.base_url, case, args.timeout_sec, args.bearer_token) for case in cases]
    for result in results:
        print(
            f"[{'OK' if result.ok else 'RISK'}] {result.case_id} "
            f"planner={result.planner} replan={result.replan_rounds} "
            f"latency={result.latency_ms:.1f} details={result.details}"
        )
    output = Path(args.out)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(report(results), encoding="utf-8")
    print(f"written to {output}")
    return 0 if all(result.ok for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
