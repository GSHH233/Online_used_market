#!/usr/bin/env python3
"""Evaluate publish-assist snapshot, adoption, product link, and risk feedback."""

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
class Result:
    case_id: str
    requirements: list[str]
    scenario: str
    ok: bool
    latency_ms: float
    details: list[str]


class Context:
    def __init__(self, base_url: str, timeout: float, tokens: dict[str, str]):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.tokens = tokens
        self.values: dict[str, Any] = {"from": datetime.now().isoformat(timespec="seconds")}

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

    def feedback(self, auth: str, body: dict[str, Any]) -> dict[str, Any]:
        response, _ = self.request(
            "POST", f"/api/ai/products/publish-assist/{self.values['assistId']}/feedback", auth, body
        )
        return response

    def metrics(self, auth: str = "internal") -> dict[str, Any]:
        value = urllib.parse.quote(str(self.values["from"]))
        response, _ = self.request("GET", f"/api/ai/products/publish-assist/metrics?from={value}", auth)
        return response


def check_code(response: dict[str, Any], expected: int) -> None:
    actual = int(response.get("code") or 0)
    if actual != expected:
        raise AssertionError(f"appCode:{actual}!={expected},response={response}")


def expect(value: bool, message: str, failures: list[str]) -> None:
    if not value:
        failures.append(message)


def run(case: dict[str, Any], ctx: Context) -> Result:
    scenario = str(case.get("scenario") or "")
    failures: list[str] = []
    started = time.perf_counter()
    try:
        if scenario == "generate_assist":
            response, _ = ctx.request("POST", "/api/ai/products/publish-assist", "user", {
                "title": "全新未拆封 iPhone 15 无发票",
                "description": "朋友赠送的全新手机，未拆封但没有购买发票，准备在平台出售。",
                "brand": "Apple", "conditionText": "全新", "categoryName": "手机", "expectedPrice": 4800
            })
            check_code(response, 200)
            data = response["data"]
            expect(bool(data.get("assistId")), "missingAssistId", failures)
            expect(bool(data.get("traceId")), "missingTraceId", failures)
            ctx.values.update({"assistId": data.get("assistId"), "traceId": data.get("traceId"), "assist": data})

        elif scenario == "traceability_fields":
            assist = ctx.values["assist"]
            expect(assist.get("assistId") == ctx.values["assistId"], "assistIdChanged", failures)
            expect(assist.get("traceId") == ctx.values["traceId"], "traceIdChanged", failures)
            expect("riskEvidence" in assist and "sources" in assist, "missingEvidenceFields", failures)

        elif scenario == "owner_get":
            response, _ = ctx.request("GET", f"/api/ai/products/publish-assist/{ctx.values['assistId']}", "user")
            check_code(response, 200)
            expect(response["data"].get("traceId") == ctx.values["traceId"], "snapshotTraceMismatch", failures)
            ctx.values["riskLevel"] = response["data"].get("riskLevel")

        elif scenario == "other_user_get_denied":
            response, _ = ctx.request("GET", f"/api/ai/products/publish-assist/{ctx.values['assistId']}", "other")
            check_code(response, 404)

        elif scenario == "partial_feedback":
            response = ctx.feedback("user", {
                "adoptionType": "PARTIAL", "adoptedFields": ["title"], "reason": "只采用标题"
            })
            check_code(response, 200)
            expect(response["data"].get("adoptionType") == "PARTIAL", "notPartial", failures)
            expect(response["data"].get("adoptedFields") == ["title"], "partialFieldsWrong", failures)

        elif scenario == "feedback_upsert":
            response = ctx.feedback("user", {
                "adoptionType": "APPLIED", "adoptedFields": ["title", "description", "riskAdvice"]
            })
            check_code(response, 200)
            expect(response["data"].get("adoptionType") == "APPLIED", "upsertFailed", failures)

        elif scenario == "ignored_with_fields_rejected":
            check_code(ctx.feedback("user", {"adoptionType": "IGNORED", "adoptedFields": ["title"]}), 400)

        elif scenario == "partial_without_fields_rejected":
            check_code(ctx.feedback("user", {"adoptionType": "PARTIAL", "adoptedFields": []}), 400)

        elif scenario == "unknown_field_rejected":
            check_code(ctx.feedback("user", {"adoptionType": "PARTIAL", "adoptedFields": ["unknown"]}), 400)

        elif scenario == "create_linked_product":
            optimized = (ctx.values["assist"].get("optimizeResult") or {})
            response, _ = ctx.request("POST", "/api/products/createProduct", "user", {
                "title": optimized.get("optimizedTitle") or "iPhone 15 二手手机",
                "description": optimized.get("optimizedDescription") or "全新未拆封，无购买发票，来源为朋友赠送。",
                "price": 4800, "originalPrice": 5999, "conditionLevel": 10,
                "locationText": "上海", "imageUrls": ["https://example.invalid/eval-product.jpg"],
                "assistId": ctx.values["assistId"], "assistAdoptionType": "APPLIED",
                "assistAdoptedFields": ["title", "description", "riskAdvice"]
            })
            check_code(response, 200)
            ctx.values["productId"] = int(response["data"]["productId"])

        elif scenario == "product_link_visible":
            response, _ = ctx.request("GET", f"/api/ai/products/publish-assist/{ctx.values['assistId']}", "user")
            check_code(response, 200)
            expect(int(response["data"].get("productId") or 0) == ctx.values["productId"], "productNotLinked", failures)
            expect(response["data"].get("adoptionType") == "APPLIED", "linkedAdoptionWrong", failures)

        elif scenario == "other_user_feedback_denied":
            check_code(ctx.feedback("other", {"adoptionType": "IGNORED", "adoptedFields": []}), 404)

        elif scenario == "internal_auto_outcome":
            response, _ = ctx.request("POST", f"/api/ai/products/{ctx.values['productId']}/risk-outcomes", "internal", {
                "outcomeType": "PENDING", "outcomeSource": "AUTO_REVIEW", "reasonCode": "NEED_REVIEW",
                "reason": "等待人工复核", "isFinal": False
            })
            check_code(response, 200)
            expect(response["data"].get("isFinal") is False, "autoOutcomeFinal", failures)

        elif scenario == "internal_final_outcome_denied":
            response, _ = ctx.request("POST", f"/api/ai/products/{ctx.values['productId']}/risk-outcomes", "internal", {
                "outcomeType": "REJECTED", "outcomeSource": "MANUAL_REVIEW", "isFinal": True
            })
            check_code(response, 403)

        elif scenario == "admin_final_rejected":
            response, _ = ctx.request("POST", f"/api/ai/products/{ctx.values['productId']}/risk-outcomes", "admin", {
                "outcomeType": "REJECTED", "outcomeSource": "MANUAL_REVIEW", "reasonCode": "MISSING_PROOF",
                "reason": "无法提供来源凭证", "isFinal": True
            })
            check_code(response, 200)
            expect(response["data"].get("isFinal") is True, "finalOutcomeNotFinal", failures)

        elif scenario == "internal_metrics":
            response = ctx.metrics("internal")
            check_code(response, 200)
            ctx.values["metricsBefore"] = response["data"]
            expect(int(response["data"].get("generatedCount") or 0) >= 1, "generatedCountZero", failures)

        elif scenario == "user_metrics_denied":
            check_code(ctx.metrics("user"), 403)

        elif scenario == "adoption_metrics":
            metrics = ctx.metrics("internal")["data"]
            expect(int(metrics.get("feedbackCount") or 0) >= 1, "feedbackCountZero", failures)
            expect(float(metrics.get("adoptionRate") or 0) > 0, "adoptionRateZero", failures)

        elif scenario == "replace_final_outcome":
            response, _ = ctx.request("POST", f"/api/ai/products/{ctx.values['productId']}/risk-outcomes", "admin", {
                "outcomeType": "PASSED", "outcomeSource": "MANUAL_REVIEW", "reasonCode": "PROOF_VERIFIED",
                "reason": "补充凭证后通过", "isFinal": True
            })
            check_code(response, 200)
            expect(response["data"].get("outcomeType") == "PASSED", "finalNotReplaced", failures)

        elif scenario == "risk_metrics":
            metrics = ctx.metrics("internal")["data"]
            if ctx.values.get("riskLevel") not in ("MEDIUM", "HIGH"):
                failures.append("fixtureNotFlaggedByRiskPipeline")
            expect(int(metrics.get("flaggedWithOutcomeCount") or 0) >= 1, "flaggedOutcomeCountZero", failures)
            expect(float(metrics.get("falsePositiveRate") or 0) > 0, "falsePositiveRateZero", failures)

        else:
            failures.append(f"unknownScenario:{scenario}")
    except Exception as exc:
        failures.append(f"error:{exc}")

    latency = (time.perf_counter() - started) * 1000
    if latency > float(case.get("maxLatencyMs") or 5000):
        failures.append(f"latencyExceeded:{latency:.1f}")
    return Result(str(case.get("caseId")), list(case.get("requirementIds") or []), scenario, not failures, latency, failures)


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    position = (len(ordered) - 1) * p / 100
    lo, hi = math.floor(position), math.ceil(position)
    return ordered[lo] if lo == hi else ordered[lo] + (ordered[hi] - ordered[lo]) * (position - lo)


def report(results: list[Result], output: Path) -> None:
    passed = sum(1 for item in results if item.ok)
    lines = ["# Publish Feedback Eval Report", "", f"- Cases: {len(results)}", f"- Passed: {passed}",
             f"- Pass rate: {passed / len(results):.2%}",
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
    parser.add_argument("--dataset", default="eval/datasets/publish_feedback_eval_dataset.json")
    parser.add_argument("--output", default="")
    parser.add_argument("--timeout-sec", type=float, default=35.0)
    parser.add_argument("--admin-bearer-token", default="")
    parser.add_argument("--internal-bearer-token", default="")
    parser.add_argument("--user-bearer-token", default="")
    parser.add_argument("--other-user-bearer-token", default="")
    add_auth_args(parser)
    args = parser.parse_args()
    tokens = {
        "admin": strip_bearer_prefix(args.admin_bearer_token),
        "internal": strip_bearer_prefix(args.internal_bearer_token),
        "user": strip_bearer_prefix(args.user_bearer_token or resolve_bearer_token(args, args.base_url, args.timeout_sec)),
        "other": strip_bearer_prefix(args.other_user_bearer_token),
    }
    cases = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    context = Context(args.base_url, args.timeout_sec, tokens)
    results = [run(case, context) for case in cases]
    output = Path(args.output) if args.output else Path(
        f"publish_feedback_eval_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md")
    report(results, output)
    for item in results:
        print(f"[{'OK' if item.ok else 'RISK'}] {item.case_id} {item.scenario} {item.latency_ms:.1f} {item.details}")
    print(f"written to {output}")
    return 0 if all(item.ok for item in results) else 1
if __name__ == "__main__":
    sys.exit(main())
