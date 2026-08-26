#!/usr/bin/env python3
"""Evaluate the unified Agent endpoint with DeepEval and project contracts.

DeepEval owns framework-generic tool correctness and least-privilege metrics.
Project assertions still own HTTP authorization, strategy routing, trace shape,
observation presence, step budgets, and DAG validity.
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

os.environ.setdefault("DEEPEVAL_TELEMETRY_OPT_OUT", "1")

try:
    from deepeval.metrics import GEval, ToolCorrectnessMetric, ToolPermissionMetric
    from deepeval.models import DeepEvalBaseLLM, GPTModel
    from deepeval.test_case import LLMTestCase, SingleTurnParams, ToolCall, ToolCallParams
except ImportError as exc:  # pragma: no cover - exercised by users without eval dependencies
    print(
        "DeepEval is not installed. Run: python -m pip install -r requirements-eval.txt",
        file=sys.stderr,
    )
    raise SystemExit(2) from exc


@dataclass
class MetricScore:
    name: str
    score: float | None
    passed: bool
    reason: str


@dataclass
class CaseResult:
    case_id: str
    role: str
    strategy: str
    tools: list[str]
    http_status: int
    app_code: int | None
    latency_ms: float
    framework_scores: list[MetricScore]
    contract_failures: list[str]
    error: str = ""

    @property
    def ok(self) -> bool:
        return not self.error and not self.contract_failures and all(
            score.passed for score in self.framework_scores
        )


class NoOpJudge(DeepEvalBaseLLM):
    """Satisfy DeepEval's model constructor without allowing hidden judge calls."""

    def load_model(self) -> "NoOpJudge":
        return self

    def generate(self, *args: Any, **kwargs: Any) -> str:
        raise RuntimeError("A deterministic DeepEval metric unexpectedly requested an LLM judge")

    async def a_generate(self, *args: Any, **kwargs: Any) -> str:
        raise RuntimeError("A deterministic DeepEval metric unexpectedly requested an LLM judge")

    def get_model_name(self) -> str:
        return "deterministic-no-op"


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def compact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)


def post_json(
    url: str,
    payload: dict[str, Any],
    timeout_sec: float,
    token: str,
) -> tuple[int, dict[str, Any], float]:
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + strip_bearer(token)
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw), (time.perf_counter() - started) * 1000
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {"message": raw}
        return exc.code, body, (time.perf_counter() - started) * 1000


def strip_bearer(token: str) -> str:
    value = token.strip()
    return value[7:].strip() if value.lower().startswith("bearer ") else value


def list_of_strings(value: Any) -> list[str]:
    return [str(item) for item in value] if isinstance(value, list) else []


def as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def graph_tool_calls(data: dict[str, Any]) -> list[ToolCall]:
    calls: list[ToolCall] = []
    for node in data.get("graph") or []:
        if not isinstance(node, dict) or str(node.get("type") or "").upper() != "TOOL":
            continue
        name = str(node.get("tool") or "")
        if name:
            calls.append(ToolCall(name=name, input_parameters=as_dict(node.get("input"))))
    return calls


def trace_tool_calls(data: dict[str, Any]) -> list[ToolCall]:
    calls: list[ToolCall] = []
    for step in data.get("agentTrace") or []:
        if not isinstance(step, dict) or str(step.get("phase") or "") != "tool_call":
            continue
        name = str(step.get("tool") or "")
        if not name:
            continue
        metadata = as_dict(step.get("metadata"))
        calls.append(ToolCall(name=name, input_parameters=as_dict(metadata.get("input"))))
    return calls


def actual_tool_calls(data: dict[str, Any]) -> list[ToolCall]:
    calls = graph_tool_calls(data)
    return calls if calls else trace_tool_calls(data)


def expected_tool_call(value: Any) -> ToolCall:
    if isinstance(value, str):
        return ToolCall(name=value)
    if isinstance(value, dict):
        return ToolCall(
            name=str(value.get("name") or ""),
            input_parameters=as_dict(value.get("inputParameters")),
        )
    raise ValueError(f"Invalid expected tool call: {value!r}")


def expected_tool_sets(case: dict[str, Any]) -> list[list[ToolCall]]:
    raw_sets = case.get("expectedToolSets")
    if not isinstance(raw_sets, list) or not raw_sets:
        return [[]]
    return [[expected_tool_call(item) for item in values] for values in raw_sets if isinstance(values, list)]


def tool_names(calls: list[ToolCall]) -> list[str]:
    return [call.name for call in calls]


def tool_correctness_score(
    question: str,
    answer: str,
    actual_calls: list[ToolCall],
    candidates: list[list[ToolCall]],
    compare_arguments: bool,
    ordered: bool,
) -> MetricScore:
    best_score = -1.0
    best_reason = ""
    for expected_calls in candidates:
        test_case = LLMTestCase(
            input=question,
            actual_output=answer or "Agent returned no final answer",
            tools_called=actual_calls,
            expected_tools=expected_calls,
        )
        metric = ToolCorrectnessMetric(
            threshold=1.0,
            evaluation_params=[ToolCallParams.INPUT_PARAMETERS] if compare_arguments else [],
            model=NoOpJudge(),
            include_reason=True,
            strict_mode=True,
            should_exact_match=True,
            should_consider_ordering=ordered,
        )
        metric.measure(test_case)
        score = float(metric.score or 0.0)
        if score > best_score:
            best_score = score
            best_reason = str(metric.reason or "")
    return MetricScore("tool_correctness", max(0.0, best_score), best_score >= 1.0, best_reason)


def tool_permission_score(
    question: str,
    answer: str,
    actual_calls: list[ToolCall],
    allowed_tools: list[str],
    denied_tools: list[str],
) -> MetricScore:
    if not allowed_tools and not denied_tools:
        return MetricScore("tool_permission", None, True, "No permission policy configured")
    test_case = LLMTestCase(
        input=question,
        actual_output=answer or "Agent returned no final answer",
        tools_called=actual_calls,
    )
    metric = ToolPermissionMetric(
        allowed_tools=allowed_tools or None,
        denied_tools=denied_tools or None,
        threshold=1.0,
        include_reason=True,
        strict_mode=True,
    )
    metric.measure(test_case)
    score = float(metric.score or 0.0)
    return MetricScore("tool_permission", score, metric.is_successful(), str(metric.reason or ""))


def optional_task_completion_score(
    case: dict[str, Any],
    data: dict[str, Any],
    judge_model: GPTModel | None,
) -> MetricScore | None:
    if judge_model is None:
        return None
    trace_summary = [
        {
            "phase": step.get("phase"),
            "tool": step.get("tool"),
            "status": step.get("status"),
            "observation": step.get("observation"),
        }
        for step in data.get("agentTrace") or []
        if isinstance(step, dict)
    ]
    actual = compact_json({"answer": data.get("answer"), "trace": trace_summary})
    expected = str(
        case.get("expectedOutcome")
        or "Complete the user's read-only investigation using relevant evidence without unauthorized actions."
    )
    test_case = LLMTestCase(
        input=str(case.get("question") or ""),
        actual_output=actual,
        expected_output=expected,
    )
    metric = GEval(
        name="task_completion",
        criteria=(
            "Judge whether the agent completed the read-only investigation requested by the user, "
            "used the execution evidence in its conclusion, and avoided unsupported claims."
        ),
        evaluation_params=[
            SingleTurnParams.INPUT,
            SingleTurnParams.ACTUAL_OUTPUT,
            SingleTurnParams.EXPECTED_OUTPUT,
        ],
        model=judge_model,
        threshold=0.7,
        async_mode=False,
    )
    metric.measure(test_case)
    score = float(metric.score or 0.0)
    return MetricScore("task_completion_judge", score, metric.is_successful(), str(metric.reason or ""))


def graph_failures(graph: Any) -> list[str]:
    if not isinstance(graph, list) or not graph:
        return ["missingGraph"]
    ids = [str(node.get("nodeId") or "") for node in graph if isinstance(node, dict)]
    failures: list[str] = []
    if len(ids) != len(set(ids)):
        failures.append("duplicateGraphNodeIds")
    known: set[str] = set()
    for node in graph:
        if not isinstance(node, dict):
            continue
        node_id = str(node.get("nodeId") or "")
        for dependency in list_of_strings(node.get("dependsOn")):
            if dependency not in known:
                failures.append(f"invalidDependency:{node_id}->{dependency}")
        known.add(node_id)
    return failures


def contract_failures(case: dict[str, Any], data: dict[str, Any], calls: list[ToolCall]) -> list[str]:
    failures: list[str] = []
    expected_strategy = str(case.get("expectedStrategy") or "")
    actual_strategy = str(data.get("strategy") or "")
    if expected_strategy and actual_strategy != expected_strategy:
        failures.append(f"strategy:{actual_strategy}!={expected_strategy}")
    phases = [
        str(step.get("phase") or "")
        for step in data.get("agentTrace") or []
        if isinstance(step, dict)
    ]
    for phase in list_of_strings(case.get("requiredPhases")):
        if phase not in phases:
            failures.append("missingPhase:" + phase)
    if case.get("requireObservation") and not data.get("observations"):
        failures.append("missingObservation")
    if case.get("requireGraph"):
        failures.extend(graph_failures(data.get("graph")))
    max_steps = int(case.get("maxSteps", 5))
    if len(calls) > max_steps:
        failures.append(f"toolBudget:{len(calls)}>{max_steps}")
    if len(tool_names(calls)) != len(set(tool_names(calls))):
        failures.append("duplicateToolCalls")
    if data.get("success") is not True:
        failures.append("agentNotSuccessful")
    if not str(data.get("answer") or "").strip():
        failures.append("emptyAnswer")
    if not str(data.get("traceId") or "").strip():
        failures.append("missingTraceId")
    return failures


def select_token(case: dict[str, Any], internal_token: str, user_token: str, admin_token: str) -> str:
    role = str(case.get("role") or "INTERNAL").upper()
    if role == "USER":
        return user_token
    if role == "ADMIN":
        return admin_token or internal_token
    return internal_token


def evaluate_case(
    base_url: str,
    case: dict[str, Any],
    timeout_sec: float,
    internal_token: str,
    user_token: str,
    admin_token: str,
    judge_model: GPTModel | None,
) -> CaseResult:
    case_id = str(case.get("caseId") or "UNKNOWN")
    role = str(case.get("role") or "INTERNAL").upper()
    expected_http = int(case.get("expectedHttpStatus", 200))
    payload = {
        "sessionId": case.get("sessionId") or "deepeval-agent",
        "question": case.get("question") or "",
        "maxSteps": int(case.get("maxSteps", 5)),
    }
    token = select_token(case, internal_token, user_token, admin_token)
    if not token:
        return CaseResult(case_id, role, "", [], 0, None, 0.0, [], [], f"missing {role} token")
    try:
        status, response, latency_ms = post_json(
            base_url.rstrip("/") + "/api/ai/agent/investigate",
            payload,
            timeout_sec,
            token,
        )
        app_code = response.get("code") if isinstance(response.get("code"), int) else None
        if expected_http != 200:
            failures = [] if status == expected_http else [f"httpStatus:{status}!={expected_http}"]
            return CaseResult(case_id, role, "DENIED", [], status, app_code, latency_ms, [], failures)
        if status != 200 or app_code != 200:
            message = response.get("message") if isinstance(response, dict) else response
            return CaseResult(
                case_id, role, "", [], status, app_code, latency_ms, [], [],
                f"request failed: HTTP {status}, appCode={app_code}, message={message}",
            )
        data = response.get("data") if isinstance(response.get("data"), dict) else {}
        calls = actual_tool_calls(data)
        question = str(case.get("question") or "")
        answer = str(data.get("answer") or "")
        framework_scores = [
            tool_correctness_score(
                question,
                answer,
                calls,
                expected_tool_sets(case),
                bool(case.get("compareArguments", False)),
                bool(case.get("ordered", False)),
            ),
            tool_permission_score(
                question,
                answer,
                calls,
                list_of_strings(case.get("allowedTools")),
                list_of_strings(case.get("deniedTools")),
            ),
        ]
        judge_score = optional_task_completion_score(case, data, judge_model)
        if judge_score:
            framework_scores.append(judge_score)
        return CaseResult(
            case_id=case_id,
            role=role,
            strategy=str(data.get("strategy") or ""),
            tools=tool_names(calls),
            http_status=status,
            app_code=app_code,
            latency_ms=latency_ms,
            framework_scores=framework_scores,
            contract_failures=contract_failures(case, data, calls),
        )
    except (urllib.error.URLError, TimeoutError, ValueError, RuntimeError, KeyError) as exc:
        return CaseResult(case_id, role, "", [], 0, None, 0.0, [], [], str(exc))


def percentile(values: list[float], percent: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((percent / 100) * (len(ordered) - 1)))))
    return ordered[index]


def aggregate_score(results: list[CaseResult], metric_name: str) -> float | None:
    values = [
        score.score
        for result in results
        for score in result.framework_scores
        if score.name == metric_name and score.score is not None
    ]
    return statistics.mean(values) if values else None


def score_text(score: float | None) -> str:
    return "N/A" if score is None else f"{score:.3f}"


def report(results: list[CaseResult], judge_enabled: bool) -> str:
    passed = sum(1 for result in results if result.ok)
    latencies = [result.latency_ms for result in results if result.latency_ms > 0]
    lines = [
        "# DeepEval Agent Evaluation Report",
        "",
        f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        "- DeepEval version: 4.1.5",
        f"- Cases: {len(results)}",
        f"- Passed: {passed}",
        f"- Pass rate: {passed / len(results):.2%}" if results else "- Pass rate: 0.00%",
        f"- Tool correctness: {score_text(aggregate_score(results, 'tool_correctness'))}",
        f"- Tool permission: {score_text(aggregate_score(results, 'tool_permission'))}",
        f"- Task completion judge: {score_text(aggregate_score(results, 'task_completion_judge'))}",
        f"- LLM judge enabled: {str(judge_enabled).lower()}",
        f"- P50 latency ms: {statistics.median(latencies) if latencies else 0:.1f}",
        f"- P95 latency ms: {percentile(latencies, 95):.1f}",
        "",
        "> DeepEval owns generic tool correctness/permission metrics. Routing, HTTP authorization, trace, observation, DAG, and step-budget checks remain deterministic project contracts.",
        "",
        "| Case | OK | Role | Strategy | Tools | Tool score | Permission | HTTP/App | Latency ms | Details |",
        "|---|---:|---|---|---|---:|---:|---|---:|---|",
    ]
    for result in results:
        by_name = {score.name: score for score in result.framework_scores}
        details = list(result.contract_failures)
        details.extend(
            f"{score.name}:{score.reason}"
            for score in result.framework_scores
            if not score.passed
        )
        if result.error:
            details.append("error:" + result.error)
        lines.append(
            f"| {result.case_id} | {str(result.ok).lower()} | {result.role} | {result.strategy} | "
            f"{result.tools} | {score_text(by_name.get('tool_correctness').score if by_name.get('tool_correctness') else None)} | "
            f"{score_text(by_name.get('tool_permission').score if by_name.get('tool_permission') else None)} | "
            f"{result.http_status}/{result.app_code} | {result.latency_ms:.1f} | `{';'.join(details)[:360]}` |"
        )
    lines.append("")
    return "\n".join(lines)


def self_test() -> int:
    actual = [ToolCall(name="prometheus_query", input_parameters={"metricKey": "latency"})]
    expected = [[ToolCall(name="prometheus_query", input_parameters={"metricKey": "latency"})]]
    correctness = tool_correctness_score("q", "a", actual, expected, True, True)
    permission = tool_permission_score("q", "a", actual, ["prometheus_query"], [])
    denied = tool_permission_score("q", "a", actual, ["log_query"], ["prometheus_query"])
    checks = [correctness.passed, permission.passed, not denied.passed]
    print(
        compact_json(
            {
                "toolCorrectness": correctness.score,
                "toolPermission": permission.score,
                "deniedPermission": denied.score,
                "passed": all(checks),
            }
        )
    )
    return 0 if all(checks) else 1


def build_judge_model(args: argparse.Namespace) -> GPTModel | None:
    if not args.enable_llm_judge:
        return None
    if not args.judge_api_key:
        raise ValueError("--enable-llm-judge requires --judge-api-key or DEEPEVAL_JUDGE_API_KEY")
    return GPTModel(
        model=args.judge_model,
        api_key=args.judge_api_key,
        base_url=args.judge_base_url or None,
        temperature=0,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate unified Agent routing with DeepEval 4.x.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/deepeval_agent_eval_dataset.json")
    parser.add_argument("--out", default="eval/reports/deepeval_agent_eval_report.md")
    parser.add_argument("--timeout-sec", type=float, default=120)
    parser.add_argument("--internal-bearer-token", default=os.environ.get("EVAL_INTERNAL_TOKEN", ""))
    parser.add_argument("--user-bearer-token", default=os.environ.get("EVAL_USER_TOKEN", ""))
    parser.add_argument("--admin-bearer-token", default=os.environ.get("EVAL_ADMIN_TOKEN", ""))
    parser.add_argument("--enable-llm-judge", action="store_true")
    parser.add_argument("--judge-model", default=os.environ.get("DEEPEVAL_JUDGE_MODEL", "gpt-4.1-mini"))
    parser.add_argument("--judge-api-key", default=os.environ.get("DEEPEVAL_JUDGE_API_KEY", ""))
    parser.add_argument("--judge-base-url", default=os.environ.get("DEEPEVAL_JUDGE_BASE_URL", ""))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    cases = load_json(Path(args.dataset))
    if not isinstance(cases, list):
        print("Dataset must be a JSON array.", file=sys.stderr)
        return 2
    try:
        judge_model = build_judge_model(args)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    results = [
        evaluate_case(
            args.base_url,
            case,
            args.timeout_sec,
            args.internal_bearer_token,
            args.user_bearer_token,
            args.admin_bearer_token,
            judge_model,
        )
        for case in cases
    ]
    for result in results:
        scores = {score.name: score.score for score in result.framework_scores}
        details = result.contract_failures + ([result.error] if result.error else [])
        print(
            f"[{'OK' if result.ok else 'RISK'}] {result.case_id} strategy={result.strategy} "
            f"tools={result.tools} scores={scores} latency={result.latency_ms:.1f} details={details}"
        )
    output = Path(args.out)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(report(results, judge_model is not None), encoding="utf-8")
    print(f"written to {output}")
    return 0 if all(result.ok for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
