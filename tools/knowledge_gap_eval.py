#!/usr/bin/env python3
"""Evaluate knowledge-gap capture, governance, regression, and metrics."""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from eval_auth import add_auth_args, resolve_bearer_token, strip_bearer_prefix


@dataclass
class CaseResult:
    case_id: str
    requirements: list[str]
    scenario: str
    ok: bool
    latency_ms: float
    details: list[str]


class Context:
    def __init__(self, args: Any, tokens: dict[str, str]):
        self.base_url = args.base_url.rstrip("/")
        self.timeout = args.timeout_sec
        self.tokens = tokens
        self.doc_id = args.doc_id
        self.doc_query = args.doc_query
        self.run_id = datetime.now().strftime("%Y%m%d%H%M%S")
        self.from_time = datetime.now().isoformat(timespec="seconds")
        self.values: dict[str, Any] = {}

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
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8")
        return json.loads(raw), (time.perf_counter() - started) * 1000

    def unresolved(self, query: str, suffix: str) -> dict[str, Any]:
        response, _ = self.request("POST", "/api/ai/knowledge/gaps/feedback/unresolved", "user", {
            "query": query, "sessionId": f"gap-eval-{self.run_id}", "traceId": f"gap-{self.run_id}-{suffix}"
        })
        require_code(response, 200)
        return response["data"]

    def rag(self, query: str, suffix: str,
            search_filter: dict[str, Any] | None = None) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "requestId": f"gap-{self.run_id}-{suffix}", "question": query, "topK": 5
        }
        if search_filter:
            payload["filter"] = search_filter
        response, _ = self.request("POST", "/api/ai/rag-chat/eval", "user", payload)
        require_code(response, 200)
        return response["data"]

    def gap(self, gap_id: int) -> dict[str, Any]:
        response, _ = self.request("GET", f"/api/ai/knowledge/gaps/{gap_id}", "internal")
        require_code(response, 200)
        return response["data"]

    def find_by_trace(self, trace_id: str) -> dict[str, Any]:
        response, _ = self.request("GET", "/api/ai/knowledge/gaps?limit=100", "internal")
        require_code(response, 200)
        for item in response.get("data") or []:
            if item.get("latestTraceId") == trace_id:
                return item
        raise AssertionError(f"gap not found by traceId={trace_id}")

    def link(self, gap_id: int) -> dict[str, Any]:
        response, _ = self.request("POST", f"/api/ai/knowledge/gaps/{gap_id}/link-doc", "internal",
                                   {"docId": self.doc_id})
        return response


def require_code(response: dict[str, Any], expected: int) -> None:
    actual = int(response.get("code") or 0)
    if actual != expected:
        raise AssertionError(f"appCode:{actual}!={expected},response={response}")


def expect(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def run_case(case: dict[str, Any], ctx: Context) -> CaseResult:
    scenario = str(case.get("scenario") or "")
    failures: list[str] = []
    started = time.perf_counter()
    try:
        if scenario == "user_management_denied":
            response, _ = ctx.request("GET", "/api/ai/knowledge/gaps", "user")
            require_code(response, 403)

        elif scenario == "internal_list":
            response, _ = ctx.request("GET", "/api/ai/knowledge/gaps?limit=5", "internal")
            require_code(response, 200)
            expect(isinstance(response.get("data"), list), "listResponseInvalid", failures)

        elif scenario == "no_retrieval_capture":
            query = f"编号{ctx.run_id}的火星量子烤箱保修规则是什么"
            no_match_filter = {"category": f"__gap_eval_no_match_{ctx.run_id}__"}
            answer = ctx.rag(query, "empty-1", no_match_filter)
            expect(answer.get("lowConfidence") is True, "fixtureNotLowConfidence", failures)
            gap = ctx.find_by_trace(str(answer.get("traceId")))
            ctx.values.update({
                "emptyQuery": query,
                "emptyGapId": gap["id"],
                "emptyFilter": no_match_filter,
            })

        elif scenario == "no_retrieval_fields":
            gap = ctx.gap(int(ctx.values["emptyGapId"]))
            expect(gap.get("reasonCode") in ("NO_RETRIEVAL", "LOW_CONFIDENCE"), "reasonCodeWrong", failures)
            expect(bool(gap.get("latestTraceId")), "missingTraceId", failures)
            expect(int(gap.get("occurrenceCount") or 0) == 1, "initialOccurrenceNot1", failures)

        elif scenario == "repeat_normalized_query":
            query = "请问" + str(ctx.values["emptyQuery"]) + "一下，谢谢"
            answer = ctx.rag(query, "empty-2", ctx.values.get("emptyFilter"))
            gap = ctx.find_by_trace(str(answer.get("traceId")))
            expect(int(gap["id"]) == int(ctx.values["emptyGapId"]), "normalizedQueryNotDeduped", failures)

        elif scenario == "dedup_occurrence":
            gap = ctx.gap(int(ctx.values["emptyGapId"]))
            expect(int(gap.get("occurrenceCount") or 0) >= 2, "occurrenceNotIncremented", failures)

        elif scenario == "missing_fields_capture":
            answer = ctx.rag("这个东西能卖吗", "missing")
            if answer.get("lowConfidence") is not True:
                failures.append("fixtureNotLowConfidence")
            gap = ctx.find_by_trace(str(answer.get("traceId")))
            ctx.values["missingGapId"] = gap["id"]

        elif scenario == "missing_fields_reason":
            gap = ctx.gap(int(ctx.values["missingGapId"]))
            expect(gap.get("reasonCode") == "MISSING_FIELDS", "notMissingFields", failures)

        elif scenario == "missing_fields_link_rejected":
            response = ctx.link(int(ctx.values["missingGapId"]))
            require_code(response, 422)

        elif scenario == "user_unresolved_capture":
            query = f"用户仍未解决的问题{ctx.run_id}"
            gap = ctx.unresolved(query, "user-1")
            expect(gap.get("reasonCode") == "USER_UNRESOLVED", "notUserUnresolved", failures)
            ctx.values.update({"userGapId": gap["id"], "userGapQuery": query})

        elif scenario == "user_unresolved_dedup":
            gap = ctx.unresolved(str(ctx.values["userGapQuery"]), "user-2")
            expect(int(gap["id"]) == int(ctx.values["userGapId"]), "userFeedbackNotDeduped", failures)
            expect(int(gap.get("occurrenceCount") or 0) >= 2, "userOccurrenceNotIncremented", failures)

        elif scenario == "phone_redaction":
            gap = ctx.unresolved(f"联系13800138000查询规则{ctx.run_id}", "pii-phone")
            expect("13800138000" not in gap.get("query", ""), "phoneNotRedacted", failures)
            ctx.values["piiGapId"] = gap["id"]

        elif scenario == "email_token_redaction":
            fake_api_token = "sk-" + "abcdefghijklmnop123456"
            gap = ctx.unresolved(f"邮箱demo@example.com token {fake_api_token}规则{ctx.run_id}", "pii-token")
            expect("demo@example.com" not in gap.get("query", ""), "emailNotRedacted", failures)
            expect(fake_api_token not in gap.get("query", ""), "tokenNotRedacted", failures)

        elif scenario == "internal_triage":
            response, _ = ctx.request("PATCH", f"/api/ai/knowledge/gaps/{ctx.values['piiGapId']}", "internal",
                                      {"status": "TRIAGED", "priority": "HIGH"})
            require_code(response, 200)
            expect(response["data"].get("status") == "TRIAGED", "triageFailed", failures)

        elif scenario == "priority_update":
            gap = ctx.gap(int(ctx.values["piiGapId"]))
            expect(gap.get("priority") == "HIGH", "priorityNotHigh", failures)

        elif scenario == "internal_dismiss_denied":
            response, _ = ctx.request("PATCH", f"/api/ai/knowledge/gaps/{ctx.values['piiGapId']}", "internal",
                                      {"status": "DISMISSED"})
            require_code(response, 403)

        elif scenario == "admin_dismiss":
            response, _ = ctx.request("PATCH", f"/api/ai/knowledge/gaps/{ctx.values['piiGapId']}", "admin",
                                      {"status": "DISMISSED"})
            require_code(response, 200)
            expect(response["data"].get("status") == "DISMISSED", "dismissFailed", failures)

        elif scenario == "create_regression_fixture":
            gap = ctx.unresolved(ctx.doc_query, "regress-ok")
            ctx.values["successGapId"] = gap["id"]

        elif scenario == "link_verified_doc":
            response = ctx.link(int(ctx.values["successGapId"]))
            require_code(response, 200)
            expect(int(response["data"].get("linkedDocId") or 0) == ctx.doc_id, "docNotLinked", failures)

        elif scenario == "regression_success":
            response, _ = ctx.request("POST", f"/api/ai/knowledge/gaps/{ctx.values['successGapId']}/regress",
                                      "internal", {})
            require_code(response, 200)
            result = response["data"]
            expect(result.get("passed") is True, "regressionNotPassed", failures)
            expect(result.get("retrievalHit") is True and result.get("sourceHit") is True,
                   "sourceAssertionsFailed", failures)
            ctx.values["successRegression"] = result

        elif scenario == "regression_persisted":
            response, _ = ctx.request("GET", f"/api/ai/knowledge/gaps/{ctx.values['successGapId']}/regressions",
                                      "internal")
            require_code(response, 200)
            expect(bool(response.get("data")), "regressionMissing", failures)
            expect(bool(ctx.values["successRegression"].get("evalRunId")), "evalRunMissing", failures)

        elif scenario == "resolved_recurrence":
            gap = ctx.unresolved(ctx.doc_query, "regress-recur")
            expect(int(gap["id"]) == int(ctx.values["successGapId"]), "resolvedGapNotReused", failures)
            expect(gap.get("status") == "OPEN", "resolvedGapNotReopened", failures)
            expect(int(gap.get("recurrenceCount") or 0) >= 1, "recurrenceNotIncremented", failures)

        elif scenario == "create_failure_fixture":
            query = f"{ctx.run_id}号银河系古董传送门保险条款"
            gap = ctx.unresolved(query, "regress-fail")
            ctx.values["failedGapId"] = gap["id"]
            require_code(ctx.link(int(gap["id"])), 200)

        elif scenario == "regression_failure":
            response, _ = ctx.request("POST", f"/api/ai/knowledge/gaps/{ctx.values['failedGapId']}/regress",
                                      "internal", {})
            require_code(response, 200)
            expect(response["data"].get("passed") is False, "failureFixtureUnexpectedlyPassed", failures)

        elif scenario == "failed_gap_not_closed":
            gap = ctx.gap(int(ctx.values["failedGapId"]))
            expect(gap.get("status") == "REGRESSION_FAILED", "failedGapClosed", failures)
            response, _ = ctx.request("GET", f"/api/ai/knowledge/gaps/{ctx.values['failedGapId']}/regressions",
                                      "internal")
            require_code(response, 200)
            expect(bool(response["data"][0].get("evalRunId")), "failedEvalRunMissing", failures)

        elif scenario == "governance_metrics":
            start = urllib.parse.quote(ctx.from_time)
            response, _ = ctx.request("GET", f"/api/ai/knowledge/gaps/metrics?from={start}", "internal")
            require_code(response, 200)
            data = response["data"]
            expect(int(data.get("totalGaps") or 0) >= 5, "totalGapsTooLow", failures)
            expect(int(data.get("ragChatTurns") or 0) >= 3, "ragTurnsTooLow", failures)
            expect(data.get("refusalRate") is not None, "refusalRateMissing", failures)

        else:
            failures.append(f"unknownScenario:{scenario}")
    except Exception as exc:
        failures.append(f"error:{exc}")

    latency = (time.perf_counter() - started) * 1000
    max_latency = float(case.get("maxLatencyMs") or 5000)
    if latency > max_latency:
        failures.append(f"latencyExceeded:{latency:.1f}>{max_latency:.1f}")
    return CaseResult(str(case.get("caseId")), list(case.get("requirementIds") or []),
                      scenario, not failures, latency, failures)


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    pos = (len(ordered) - 1) * p / 100
    lo, hi = math.floor(pos), math.ceil(pos)
    return ordered[lo] if lo == hi else ordered[lo] + (ordered[hi] - ordered[lo]) * (pos - lo)


def write_report(results: list[CaseResult], output: Path) -> None:
    passed = sum(1 for item in results if item.ok)
    lines = ["# Knowledge Gap Eval Report", "", f"- Cases: {len(results)}", f"- Passed: {passed}",
             f"- Capture/Dedup/Governance pass rate: {passed / len(results):.2%}",
             f"- P95 latency ms: {percentile([x.latency_ms for x in results], 95):.1f}", "",
             "| Case | Requirements | Scenario | OK | Latency ms | Details |",
             "|---|---|---|---:|---:|---|"]
    for item in results:
        lines.append(f"| {item.case_id} | {','.join(item.requirements)} | {item.scenario} | "
                     f"{str(item.ok).lower()} | {item.latency_ms:.1f} | `{','.join(item.details)}` |")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/knowledge_gap_eval_dataset.json")
    parser.add_argument("--output", default="")
    parser.add_argument("--timeout-sec", type=float, default=45.0)
    parser.add_argument("--doc-id", type=int, default=19)
    parser.add_argument("--doc-query", default="二手手机标题写全新未拆封无发票会有风险吗")
    parser.add_argument("--admin-bearer-token", default="")
    parser.add_argument("--internal-bearer-token", default="")
    parser.add_argument("--user-bearer-token", default="")
    add_auth_args(parser)
    args = parser.parse_args()
    tokens = {
        "admin": strip_bearer_prefix(args.admin_bearer_token),
        "internal": strip_bearer_prefix(args.internal_bearer_token),
        "user": strip_bearer_prefix(args.user_bearer_token or resolve_bearer_token(args, args.base_url, args.timeout_sec)),
    }
    cases = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    context = Context(args, tokens)
    results = [run_case(case, context) for case in cases]
    output = Path(args.output) if args.output else Path(
        f"knowledge_gap_eval_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md")
    write_report(results, output)
    for item in results:
        print(f"[{'OK' if item.ok else 'RISK'}] {item.case_id} {item.scenario} {item.latency_ms:.1f} {item.details}")
    print(f"written to {output}")
    return 0 if all(item.ok for item in results) else 1


if __name__ == "__main__":
    sys.exit(main())
