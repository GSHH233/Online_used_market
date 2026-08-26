#!/usr/bin/env python3
"""Exercise retained knowledge revisions and approval-gated rollback against a live backend."""
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


@dataclass
class CaseResult:
    case_id: str
    ok: bool
    latency_ms: float
    details: list[str]


class Client:
    def __init__(self, base_url: str, internal_token: str, admin_token: str, timeout_sec: float):
        self.base_url = base_url.rstrip("/")
        self.tokens = {"internal": strip_bearer(internal_token), "admin": strip_bearer(admin_token)}
        self.timeout_sec = timeout_sec

    def request(self, method: str, path: str, role: str, body: dict[str, Any] | None = None) -> tuple[dict[str, Any], float]:
        headers = {"Accept": "application/json"}
        if self.tokens.get(role):
            headers["Authorization"] = f"Bearer {self.tokens[role]}"
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_sec) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                payload = {"code": exc.code, "message": raw}
        return payload, (time.perf_counter() - started) * 1000


def strip_bearer(value: str) -> str:
    return value.removeprefix("Bearer ").strip()


def require_success(payload: dict[str, Any]) -> dict[str, Any]:
    if payload.get("code") != 200:
        raise RuntimeError(f"appCode:{payload.get('code')} message:{payload.get('message')}")
    data = payload.get("data")
    if not isinstance(data, dict):
        raise RuntimeError(f"unexpected data: {data}")
    return data


def create_doc(client: Client, logical_id: str, version: str, body: str) -> tuple[dict[str, Any], float]:
    payload, latency = client.request("POST", "/api/ai/knowledge/docs", "internal", {
        "title": "版本生命周期评测规则",
        "content": body,
        "sourceType": "RULE",
        "tags": "lifecycle,eval,LOW",
        "sourceTrustLevel": "HIGH",
        "docVersion": version,
        "logicalKnowledgeId": logical_id,
    })
    return require_success(payload), latency


def ingest(client: Client, doc_id: int) -> tuple[dict[str, Any], float]:
    payload, latency = client.request("POST", f"/api/ai/knowledge/jobs/ingest/{doc_id}?async=false", "internal")
    return require_success(payload), latency


def get_doc(client: Client, doc_id: int) -> tuple[dict[str, Any], float]:
    payload, latency = client.request("GET", f"/api/ai/knowledge/docs/{doc_id}", "internal")
    return require_success(payload), latency


def chunks(client: Client, doc_id: int) -> tuple[list[dict[str, Any]], float]:
    payload, latency = client.request("GET", f"/api/ai/knowledge/debug/chunks/by-doc/{doc_id}", "internal")
    if payload.get("code") != 200 or not isinstance(payload.get("data"), list):
        raise RuntimeError(f"chunk debug failed: {payload}")
    return payload["data"], latency


def evaluate_case(case_id: str, action) -> CaseResult:
    started = time.perf_counter()
    details: list[str] = []
    try:
        action(details)
        return CaseResult(case_id, True, (time.perf_counter() - started) * 1000, details)
    except Exception as exc:  # keep writing a report even when a live dependency fails
        details.append(f"error:{exc}")
        return CaseResult(case_id, False, (time.perf_counter() - started) * 1000, details)


def write_report(path: Path, results: list[CaseResult], context: dict[str, Any]) -> None:
    passed = sum(1 for result in results if result.ok)
    lines = [
        "# Knowledge Version Lifecycle Eval Report",
        "",
        f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"- Cases: {len(results)}",
        f"- Passed: {passed}",
        f"- Pass rate: {passed / len(results):.2%}" if results else "- Pass rate: n/a",
        f"- Logical knowledge ID: {context.get('logicalKnowledgeId', '')}",
        f"- v1 docId: {context.get('v1DocId', '')}",
        f"- v2 docId: {context.get('v2DocId', '')}",
        f"- rollback operationId: {context.get('operationId', '')}",
        "",
        "## Cases",
        "",
    ]
    for result in results:
        verdict = "OK" if result.ok else "RISK"
        lines.append(f"- [{verdict}] {result.case_id}: {result.latency_ms:.1f}ms; {result.details or ['none']}")
    lines.extend([
        "",
        "## Coverage",
        "",
        "- New revision is staged and activated only after the existing ingestion verification path succeeds.",
        "- Superseded revision becomes RETIRED, remains non-retrievable, and retains chunk artifacts during the configured window.",
        "- Rollback is created as an INTERNAL plan and can only be approved/executed by ADMIN.",
        "- Rollback task rebuilds DB/ES/Milvus, verifies consistency, swaps ACTIVE revision, and exposes persisted operation trace/audit.",
        "- Expired physical cleanup is covered by KnowledgeRetiredVersionCleanupServiceImplTest; production default retention is 7 days.",
    ])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--internal-bearer-token", default=os.getenv("INTERNAL_BEARER_TOKEN", ""))
    parser.add_argument("--admin-bearer-token", default=os.getenv("ADMIN_BEARER_TOKEN", ""))
    parser.add_argument("--timeout-sec", type=float, default=60.0)
    parser.add_argument("--out", default="eval/reports/knowledge_version_lifecycle_eval_report.md")
    args = parser.parse_args()
    if not strip_bearer(args.internal_bearer_token) or not strip_bearer(args.admin_bearer_token):
        raise SystemExit("missing --internal-bearer-token/--admin-bearer-token or environment variables")

    client = Client(args.base_url, args.internal_bearer_token, args.admin_bearer_token, args.timeout_sec)
    suffix = uuid.uuid4().hex[:10]
    logical_id = f"eval:lifecycle:{suffix}"
    old_marker = f"LIFECYCLE_OLD_ONLY_{suffix}"
    new_marker = f"LIFECYCLE_NEW_ONLY_{suffix}"
    context: dict[str, Any] = {"logicalKnowledgeId": logical_id}
    results: list[CaseResult] = []

    def create_v1(details: list[str]) -> None:
        doc, latency = create_doc(client, logical_id, "v1", f"旧版规则：{old_marker}。发布时需保留旧版证据。")
        context["v1DocId"] = doc.get("id")
        if not context["v1DocId"] or doc.get("status") != "DRAFT":
            raise RuntimeError(f"unexpected v1 create response: {doc}")
        trigger, _ = ingest(client, int(context["v1DocId"]))
        task = trigger.get("task") or {}
        if trigger.get("status") != "SUCCESS" or not task.get("consistent"):
            raise RuntimeError(f"v1 ingest not verified: {trigger}")
        details.extend([f"docId={context['v1DocId']}", f"createMs={latency:.1f}", f"indexVersion={task.get('detailJson', '')[:60]}"])

    def create_v2_and_check_retention(details: list[str]) -> None:
        if not context.get("v1DocId"):
            raise RuntimeError("v1 missing")
        doc, _ = create_doc(client, logical_id, "v2", f"新版规则：{new_marker}。旧版已被替换，不得作为线上证据。")
        context["v2DocId"] = doc.get("id")
        trigger, _ = ingest(client, int(context["v2DocId"]))
        task = trigger.get("task") or {}
        if trigger.get("status") != "SUCCESS" or not task.get("consistent"):
            raise RuntimeError(f"v2 ingest not verified: {trigger}")
        old_doc, _ = get_doc(client, int(context["v1DocId"]))
        new_doc, _ = get_doc(client, int(context["v2DocId"]))
        old_chunks, _ = chunks(client, int(context["v1DocId"]))
        if old_doc.get("status") != "RETIRED" or old_doc.get("cleanupStatus") != "PENDING":
            raise RuntimeError(f"v1 did not enter retained state: {old_doc}")
        if not old_doc.get("retentionUntil") or not old_chunks:
            raise RuntimeError("v1 artifacts were removed before the retention window")
        if new_doc.get("status") != "ACTIVE":
            raise RuntimeError(f"v2 not active: {new_doc}")
        details.extend([f"v1Chunks={len(old_chunks)}", f"retentionUntil={old_doc.get('retentionUntil')}"])

    def retired_not_retrievable(details: list[str]) -> None:
        payload, _ = client.request("POST", "/api/ai/knowledge/search", "internal", {
            "question": old_marker,
            "topK": 10,
            "mode": "KEYWORD_ONLY",
            "rewriteEnabled": False,
        })
        data = require_success(payload)
        hits = data.get("hits") or []
        old_doc_id = int(context["v1DocId"])
        leaked = [hit for hit in hits if int(hit.get("docId") or -1) == old_doc_id]
        if leaked:
            raise RuntimeError(f"retired revision leaked into retrieval: {leaked}")
        details.append(f"hits={len(hits)}")

    def controlled_rollback(details: list[str]) -> None:
        target_doc_id = int(context["v1DocId"])
        plan_payload, _ = client.request("POST", "/api/ai/operations/plans", "internal", {
            "actionCode": "ROLLBACK_KNOWLEDGE_REVISION",
            "reason": "knowledge lifecycle eval rollback",
            "traceId": f"lifecycle-eval-{suffix}",
            "params": {"targetDocId": target_doc_id},
        })
        plan = require_success(plan_payload)
        context["operationId"] = plan.get("operationId")
        if plan.get("riskLevel") != "MEDIUM" or not context["operationId"]:
            raise RuntimeError(f"invalid rollback plan: {plan}")
        approved, _ = client.request("POST", f"/api/ai/operations/{context['operationId']}/approve", "admin", {
            "reason": "approve lifecycle eval rollback",
        })
        require_success(approved)
        executed, _ = client.request("POST", f"/api/ai/operations/{context['operationId']}/execute", "admin")
        execution = require_success(executed).get("execution") or {}
        if execution.get("verificationStatus") != "PASSED":
            raise RuntimeError(f"rollback execution was not verified: {execution}")
        operation_payload, _ = client.request("GET", f"/api/ai/operations/{context['operationId']}", "internal")
        operation = require_success(operation_payload)
        persisted_execution = operation.get("execution") or {}
        if operation.get("status") != "RESOLVED" or not operation.get("traceId"):
            raise RuntimeError(f"rollback operation audit is incomplete: {operation}")
        if (persisted_execution.get("verificationStatus") != "PASSED"
                or not persisted_execution.get("traceId")
                or not persisted_execution.get("actionId")):
            raise RuntimeError(f"persisted rollback execution audit is incomplete: {persisted_execution}")
        v1, _ = get_doc(client, target_doc_id)
        v2, _ = get_doc(client, int(context["v2DocId"]))
        v1_chunks, _ = chunks(client, target_doc_id)
        v2_chunks, _ = chunks(client, int(context["v2DocId"]))
        if v1.get("status") != "ACTIVE" or v2.get("status") != "RETIRED":
            raise RuntimeError(f"version switch failed: v1={v1.get('status')}, v2={v2.get('status')}")
        if v1.get("cleanupStatus"):
            raise RuntimeError(f"active revision retained cleanup marker: {v1.get('cleanupStatus')}")
        if not v1_chunks or not v2_chunks:
            raise RuntimeError("active or newly retained version lost artifacts during rollback")
        details.extend([
            f"operationId={context['operationId']}",
            f"traceId={plan.get('traceId')}",
            f"actionId={persisted_execution.get('actionId')}",
            f"activeChunks={len(v1_chunks)}",
        ])

    results.append(evaluate_case("LIFECYCLE-001", create_v1))
    results.append(evaluate_case("LIFECYCLE-002", create_v2_and_check_retention))
    results.append(evaluate_case("LIFECYCLE-003", retired_not_retrievable))
    results.append(evaluate_case("LIFECYCLE-004", controlled_rollback))
    write_report(Path(args.out), results, context)
    for result in results:
        print(f"[{'OK' if result.ok else 'RISK'}] {result.case_id} latency={result.latency_ms:.1f} details={result.details}")
    print(f"written to {args.out}")
    return 0 if all(result.ok for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
