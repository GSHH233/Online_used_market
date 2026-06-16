#!/usr/bin/env python3
"""
Evaluate AIOps Agent + Prometheus + Harness quality.

Example:
  python tools/aiops_eval.py ^
    --base-url http://localhost:8080 ^
    --dataset eval/datasets/aiops_eval_dataset.json ^
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
class AiopsCaseResult:
    case_id: str
    endpoint: str
    ok: bool
    tool_ok: bool
    denied_ok: bool
    trace_ok: bool
    graph_ok: bool
    observation_ok: bool
    harness_ok: bool
    answer_ok: bool
    latency_ok: bool
    prometheus_ok: bool
    error: str
    latency_ms: float
    returned_tools: list[str]
    denied_tools: list[str]
    harness_tools: list[str]
    graph_nodes: list[str]
    trace_phases: list[str]
    trace_statuses: list[str]
    observation_rows: int
    answer_text: str
    detail_failures: list[str]


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


def compact_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False)


def lower_set(items: list[str]) -> set[str]:
    return {x.lower() for x in items}


def contains_all(items: list[str], expected: list[str]) -> bool:
    returned = lower_set(items)
    return all(x.lower() in returned for x in expected)


def text_contains_all(text: str, expected: list[str]) -> bool:
    lower = text.lower()
    return all(x.lower() in lower for x in expected)


def extract_trace(data: dict[str, Any]) -> tuple[list[str], list[str], list[str], list[str], list[str]]:
    tools: list[str] = []
    denied_tools: list[str] = []
    harness_tools: list[str] = []
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
        if tool and status.upper() == "DENIED":
            denied_tools.append(tool)
        metadata = step.get("metadata") if isinstance(step.get("metadata"), dict) else {}
        if tool and metadata.get("harness") is True:
            harness_tools.append(tool)
    for obs in data.get("observations") or []:
        if isinstance(obs, dict) and obs.get("tool"):
            tool = str(obs.get("tool"))
            if tool not in tools:
                tools.append(tool)
    return tools, denied_tools, harness_tools, phases, statuses


def extract_graph_nodes(data: dict[str, Any]) -> list[str]:
    nodes: list[str] = []
    for node in data.get("graph") or []:
        if not isinstance(node, dict):
            continue
        node_id = str(node.get("nodeId") or "")
        tool = str(node.get("tool") or "")
        if node_id:
            nodes.append(node_id)
        if tool and tool not in nodes:
            nodes.append(tool)
    return nodes


def build_observation_text(data: dict[str, Any]) -> tuple[str, int]:
    parts: list[str] = []
    row_count = 0
    for obs in data.get("observations") or []:
        if not isinstance(obs, dict):
            continue
        parts.append(compact_text(obs.get("tool")))
        parts.append(compact_text(obs.get("target")))
        parts.append(compact_text(obs.get("summary")))
        rows = obs.get("rows") or []
        if isinstance(rows, list):
            row_count += len(rows)
            for row in rows[:5]:
                parts.append(compact_text(row))
    return "\n".join(x for x in parts if x), row_count


def trace_metadata_complete(data: dict[str, Any], required_tools: list[str], failures: list[str]) -> bool:
    if not required_tools:
        return True
    ok = True
    for tool in required_tools:
        matched = False
        for step in data.get("agentTrace") or []:
            if not isinstance(step, dict) or str(step.get("tool") or "") != tool:
                continue
            metadata = step.get("metadata") if isinstance(step.get("metadata"), dict) else {}
            if metadata.get("harness") is True:
                matched = True
                required_keys = ["attempts", "retryCount", "timeout", "fallbackUsed", "snapshotRestored", "elapsedMs"]
                missing = [key for key in required_keys if key not in metadata]
                if missing:
                    ok = False
                    failures.append(f"harnessMetadataMissing:{tool}:{missing}")
            else:
                ok = False
                failures.append(f"harnessMissing:{tool}")
        if not matched:
            ok = False
            failures.append(f"harnessTraceMissing:{tool}")
    return ok


def forbidden_harness_ok(data: dict[str, Any], forbid_tools: list[str], failures: list[str]) -> bool:
    ok = True
    for step in data.get("agentTrace") or []:
        if not isinstance(step, dict):
            continue
        tool = str(step.get("tool") or "")
        if tool not in forbid_tools:
            continue
        metadata = step.get("metadata") if isinstance(step.get("metadata"), dict) else {}
        if metadata.get("harness") is True:
            ok = False
            failures.append(f"forbiddenHarnessUsed:{tool}")
    return ok


def evaluate_case(base_url: str, case: dict[str, Any], timeout_sec: float, bearer_token: str) -> AiopsCaseResult:
    case_id = str(case.get("caseId", "UNKNOWN"))
    endpoint = str(case.get("endpoint", "graph")).lower()
    failures: list[str] = []
    url, payload = build_request(base_url, endpoint, case)
    try:
        resp, latency_ms = post_json(url, payload, timeout_sec, bearer_token)
        app_code = resp.get("code")
        app_message = str(resp.get("message") or "")
        if app_code != 200:
            return empty_result(case_id, endpoint, latency_ms, f"APP code={app_code}, message={app_message}")
        data = resp.get("data") or {}
        if not isinstance(data, dict):
            return empty_result(case_id, endpoint, latency_ms, "EMPTY data")

        if endpoint == "prometheus":
            data = wrap_prometheus_tool_data(data)

        returned_tools, denied_tools, harness_tools, phases, statuses = extract_trace(data)
        if endpoint == "prometheus":
            returned_tools = ["prometheus_query"]
        graph_nodes = extract_graph_nodes(data)
        observation_text, row_count = build_observation_text(data)
        answer = compact_text(data.get("answer"))
        eval_text = "\n".join([answer, observation_text, compact_text(data)])

        expected_tools = as_str_list(case.get("expectedTools"))
        forbid_tools = as_str_list(case.get("forbidTools"))
        tool_ok = contains_all(returned_tools, expected_tools) and not any(tool in returned_tools for tool in forbid_tools)
        if not contains_all(returned_tools, expected_tools):
            failures.append("missingTools:" + ",".join(sorted(set(expected_tools) - set(returned_tools))))
        used_forbidden = [tool for tool in forbid_tools if tool in returned_tools]
        if used_forbidden:
            failures.append("forbiddenTools:" + ",".join(used_forbidden))

        expected_denied = as_str_list(case.get("expectedDeniedTools"))
        denied_ok = contains_all(denied_tools, expected_denied)
        if not denied_ok:
            failures.append("missingDeniedTools:" + ",".join(sorted(set(expected_denied) - set(denied_tools))))

        min_trace = int(case.get("minTraceSteps", 0))
        required_phases = as_str_list(case.get("requiredPhases"))
        if not required_phases:
            required_phases = [] if endpoint == "prometheus" else (["graph_plan", "final"] if endpoint == "graph" else ["plan", "final"])
        trace_ok = len(phases) >= min_trace and contains_all(phases, required_phases) and all(status for status in statuses)
        if not trace_ok:
            failures.append(f"traceInvalid:len={len(phases)},phases={phases},statuses={statuses},required={required_phases}")

        expected_nodes = as_str_list(case.get("expectedGraphNodes"))
        graph_ok = endpoint != "graph" or contains_all(graph_nodes, expected_nodes)
        if not graph_ok:
            failures.append("missingGraphNodes:" + ",".join(sorted(set(expected_nodes) - set(graph_nodes))))

        min_observations = int(case.get("minObservations", 0))
        observation_ok = len(data.get("observations") or []) >= min_observations
        expected_obs_keywords = as_str_list(case.get("expectedObservationKeywords"))
        if expected_obs_keywords:
            observation_ok = observation_ok and text_contains_all(eval_text, expected_obs_keywords)
        if not observation_ok:
            failures.append(f"observationInvalid:obs={len(data.get('observations') or [])},rows={row_count},keywords={expected_obs_keywords}")

        required_harness = as_str_list(case.get("requiredHarnessTools"))
        forbid_harness = as_str_list(case.get("forbidHarnessTools"))
        harness_ok = trace_metadata_complete(data, required_harness, failures) and forbidden_harness_ok(data, forbid_harness, failures)

        expected_answer_keywords = as_str_list(case.get("expectedAnswerKeywords"))
        answer_ok = text_contains_all(eval_text, expected_answer_keywords)
        if not answer_ok:
            failures.append("missingAnswerKeywords:" + ",".join(expected_answer_keywords))

        min_prom_rows = int(case.get("minPrometheusRows", 0))
        allow_zero_rows = bool(case.get("allowZeroRows", False))
        prometheus_ok = True
        if endpoint == "prometheus":
            prometheus_ok = allow_zero_rows or row_count >= min_prom_rows
            if not prometheus_ok:
                failures.append(f"prometheusRowsTooLow:{row_count}<{min_prom_rows}")

        max_latency_ms = float(case.get("maxLatencyMs") or 0)
        latency_ok = max_latency_ms <= 0 or latency_ms <= max_latency_ms
        if not latency_ok:
            failures.append(f"latencyExceeded:{latency_ms:.1f}>{max_latency_ms:.1f}")

        ok = all([tool_ok, denied_ok, trace_ok, graph_ok, observation_ok, harness_ok, answer_ok, latency_ok, prometheus_ok])
        return AiopsCaseResult(
            case_id=case_id,
            endpoint=endpoint,
            ok=ok,
            tool_ok=tool_ok,
            denied_ok=denied_ok,
            trace_ok=trace_ok,
            graph_ok=graph_ok,
            observation_ok=observation_ok,
            harness_ok=harness_ok,
            answer_ok=answer_ok,
            latency_ok=latency_ok,
            prometheus_ok=prometheus_ok,
            error="",
            latency_ms=latency_ms,
            returned_tools=list(dict.fromkeys(returned_tools)),
            denied_tools=list(dict.fromkeys(denied_tools)),
            harness_tools=list(dict.fromkeys(harness_tools)),
            graph_nodes=graph_nodes,
            trace_phases=phases,
            trace_statuses=statuses,
            observation_rows=row_count,
            answer_text=answer,
            detail_failures=failures,
        )
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
        return empty_result(case_id, endpoint, 0, str(exc))


def build_request(base_url: str, endpoint: str, case: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    if endpoint == "react":
        url = base_url.rstrip("/") + "/api/ai/agent/react-chat"
        payload = {
            "sessionId": case.get("sessionId", "aiops-eval"),
            "traceId": case.get("traceId", ""),
            "mode": case.get("mode", "INTERNAL"),
            "question": case.get("question", ""),
            "maxSteps": case.get("maxSteps", 5),
        }
        return url, payload
    if endpoint == "prometheus":
        url = base_url.rstrip("/") + "/api/ai/tools/prometheus/query"
        payload = {
            "query": case.get("query", "up"),
            "queryType": case.get("queryType", "instant"),
            "time": case.get("time"),
            "start": case.get("start"),
            "end": case.get("end"),
            "step": case.get("step"),
            "limit": case.get("limit", 20),
        }
        return url, {k: v for k, v in payload.items() if v is not None}
    url = base_url.rstrip("/") + "/api/ai/agent/graph-chat"
    payload = {
        "sessionId": case.get("sessionId", "aiops-eval"),
        "traceId": case.get("traceId", ""),
        "mode": case.get("mode", "INTERNAL"),
        "question": case.get("question", ""),
        "maxSteps": case.get("maxSteps", 6),
    }
    return url, payload


def wrap_prometheus_tool_data(data: dict[str, Any]) -> dict[str, Any]:
    return {
        "answer": data.get("summary", ""),
        "agentTrace": [],
        "graph": [],
        "observations": [data],
        "sources": [],
    }


def empty_result(case_id: str, endpoint: str, latency_ms: float, error: str) -> AiopsCaseResult:
    return AiopsCaseResult(
        case_id=case_id,
        endpoint=endpoint,
        ok=False,
        tool_ok=False,
        denied_ok=False,
        trace_ok=False,
        graph_ok=False,
        observation_ok=False,
        harness_ok=False,
        answer_ok=False,
        latency_ok=False,
        prometheus_ok=False,
        error=error,
        latency_ms=latency_ms,
        returned_tools=[],
        denied_tools=[],
        harness_tools=[],
        graph_nodes=[],
        trace_phases=[],
        trace_statuses=[],
        observation_rows=0,
        answer_text="",
        detail_failures=[error] if error else [],
    )


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    sorted_values = sorted(values)
    index = min(len(sorted_values) - 1, max(0, int(round((p / 100) * (len(sorted_values) - 1)))))
    return sorted_values[index]


def rate(results: list[AiopsCaseResult], attr: str) -> float:
    if not results:
        return 0.0
    return sum(1 for r in results if getattr(r, attr)) / len(results)


def markdown_report(results: list[AiopsCaseResult]) -> str:
    total = len(results)
    latencies = [r.latency_ms for r in results if r.latency_ms > 0]
    lines: list[str] = []
    lines.append("# AIOps Agent Evaluation Report")
    lines.append("")
    lines.append(f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Cases: {total}")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Success rate: {sum(1 for r in results if r.ok) / total:.2%}")
    lines.append(f"- Tool selection accuracy: {rate(results, 'tool_ok'):.2%}")
    lines.append(f"- Denied tool accuracy: {rate(results, 'denied_ok'):.2%}")
    lines.append(f"- Trace completeness: {rate(results, 'trace_ok'):.2%}")
    lines.append(f"- Graph node coverage: {rate(results, 'graph_ok'):.2%}")
    lines.append(f"- Observation pass rate: {rate(results, 'observation_ok'):.2%}")
    lines.append(f"- Harness metadata pass rate: {rate(results, 'harness_ok'):.2%}")
    lines.append(f"- Answer keyword pass rate: {rate(results, 'answer_ok'):.2%}")
    lines.append(f"- Prometheus direct pass rate: {rate(results, 'prometheus_ok'):.2%}")
    lines.append(f"- Latency budget pass rate: {rate(results, 'latency_ok'):.2%}")
    lines.append(f"- Errors: {sum(1 for r in results if r.error)}")
    lines.append(f"- P50 latency ms: {statistics.median(latencies) if latencies else 0:.1f}")
    lines.append(f"- P95 latency ms: {percentile(latencies, 95):.1f}")
    lines.append("")
    lines.append("## Failed Or Risky Cases")
    lines.append("")
    lines.append("| Case | Endpoint | OK | Tool | Denied | Trace | Graph | Obs | Harness | Answer | Prom | Latency | Tools | Harness Tools | Rows | Details | Error |")
    lines.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---:|---|---|")
    for r in results:
        if r.ok:
            continue
        lines.append(
            f"| {r.case_id} | {r.endpoint} | {str(r.ok).lower()} | {str(r.tool_ok).lower()} | "
            f"{str(r.denied_ok).lower()} | {str(r.trace_ok).lower()} | {str(r.graph_ok).lower()} | "
            f"{str(r.observation_ok).lower()} | {str(r.harness_ok).lower()} | {str(r.answer_ok).lower()} | "
            f"{str(r.prometheus_ok).lower()} | {str(r.latency_ok).lower()} | {r.returned_tools} | "
            f"{r.harness_tools} | {r.observation_rows} | `{';'.join(r.detail_failures)[:220]}` | `{r.error[:120]}` |"
        )
    lines.append("")
    lines.append("## Slowest Cases")
    lines.append("")
    lines.append("| Case | Endpoint | OK | Latency ms | Tools |")
    lines.append("|---|---|---:|---:|---|")
    for r in sorted(results, key=lambda item: item.latency_ms, reverse=True)[:8]:
        lines.append(f"| {r.case_id} | {r.endpoint} | {str(r.ok).lower()} | {r.latency_ms:.1f} | {r.returned_tools} |")
    lines.append("")
    lines.append("## Per Case")
    lines.append("")
    lines.append("| Case | Endpoint | OK | Tools | Denied | Harness | Phases | Statuses | Rows | Latency ms | Answer Preview |")
    lines.append("|---|---|---:|---|---|---|---|---|---:|---:|---|")
    for r in results:
        preview = r.answer_text.replace("|", "\\|").replace("\n", " ")[:180]
        lines.append(
            f"| {r.case_id} | {r.endpoint} | {str(r.ok).lower()} | {r.returned_tools} | "
            f"{r.denied_tools} | {r.harness_tools} | {r.trace_phases} | {r.trace_statuses} | "
            f"{r.observation_rows} | {r.latency_ms:.1f} | {preview} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate AIOps Agent + Prometheus + Harness.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/aiops_eval_dataset.json")
    parser.add_argument("--timeout-sec", type=float, default=120)
    add_auth_args(parser)
    parser.add_argument("--out", default="")
    args = parser.parse_args()
    args.bearer_token = resolve_bearer_token(args, args.base_url, args.timeout_sec)

    cases = load_json(Path(args.dataset))
    if not isinstance(cases, list):
        print("Dataset must be a JSON array.", file=sys.stderr)
        return 2

    results: list[AiopsCaseResult] = []
    for case in cases:
        result = evaluate_case(args.base_url, case, args.timeout_sec, args.bearer_token)
        results.append(result)
        status = "OK" if result.ok else "RISK"
        print(
            f"{result.case_id:10s} {status:4s} endpoint={result.endpoint:10s} "
            f"tool={result.tool_ok} denied={result.denied_ok} trace={result.trace_ok} "
            f"obs={result.observation_ok} harness={result.harness_ok} {result.latency_ms:.1f}ms "
            f"details={result.detail_failures[:2]}"
        )

    report = markdown_report(results)
    if args.out:
        out_path = Path(args.out)
    else:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        out_path = Path(f"aiops_eval_report_{stamp}.md")
    out_path.write_text(report, encoding="utf-8")
    print(f"\nReport written: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
