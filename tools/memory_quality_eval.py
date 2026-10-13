#!/usr/bin/env python3
"""Run the MEM-06 five-layer memory quality suite against isolated accounts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import statistics
import subprocess
import time
import urllib.parse
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any

from memory_lifecycle_eval import create_synthetic_admin, mysql_connection, request_json


REQUIREMENT_ID = "MEM-AC-006"


def mutate(base: str, token: str, event: dict[str, Any], case_id: str) -> dict[str, Any]:
    payload = {
        "sessionId": case_id,
        "memoryType": event.get("memoryType", "FACT"),
        "content": event.get("content", ""),
        "importance": event.get("importance", 0.3),
        "metadataJson": json.dumps({
            "evalCaseId": case_id,
            "alias": event.get("payloadAlias") or event["alias"],
        }, ensure_ascii=False),
        "sourceEventId": event.get("eventId") or f"{case_id}-{event['alias']}",
        "sourceType": "EVAL",
        "assertionStatus": "CONFIRMED",
        "scopeType": "USER",
        "scopeKey": "USER",
        "slotKey": event.get("slotKey") or event["alias"],
    }
    if event.get("operation") == "DELETE":
        body = {key: payload[key] for key in ("memoryType", "sourceEventId", "scopeType", "scopeKey", "slotKey")}
        return request_json(base, "/api/ai/l2-memory/debug/slot", "DELETE", body, token, 20.0)
    return request_json(base, "/api/ai/l2-memory/debug/slot", "POST", payload, token, 20.0)


def recall(base: str, token: str, case: dict[str, Any]) -> dict[str, Any]:
    spec = case.get("query") or {}
    params = urllib.parse.urlencode([
        ("sessionId", case.get("sessionAlias", case["caseId"])),
        ("q", spec.get("text", "")),
        ("memoryTypes", "FACT"),
        ("memoryTypes", "PREFERENCE"),
        ("topK", str(spec.get("topK", 5))),
        ("threshold", str(spec.get("threshold", 0.4))),
        ("candidateLimit", "1000"),
        ("forceLexicalFallback", "false"),
    ])
    body = request_json(base, "/api/ai/l2-memory/debug/recall?" + params, token=token, timeout=20.0)
    if body.get("code") != 200:
        raise RuntimeError(f"recall failed: {body}")
    return body.get("data") or {}


def assemble_context(base: str, token: str, case: dict[str, Any]) -> dict[str, Any]:
    spec = case.get("query") or {}
    params = urllib.parse.urlencode({
        "sessionId": case.get("sessionAlias", case["caseId"]),
        "q": spec.get("text", ""),
    })
    body = request_json(
        base, "/api/ai/agent/runtime-context/debug?" + params,
        token=token, timeout=20.0,
    )
    if body.get("code") != 200:
        raise RuntimeError(f"context assembly failed: {body}")
    return body.get("data") or {}


def rows_for_case(user_ids: list[int], case_id: str) -> list[dict[str, Any]]:
    with mysql_connection() as connection, connection.cursor() as cursor:
        placeholders = ",".join(["%s"] * len(user_ids))
        cursor.execute(
            f"SELECT id,user_id,content,slot_key,status,source_event_id,metadata_json "
            f"FROM ai_l2_memory_record WHERE user_id IN ({placeholders}) "
            "AND JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.evalCaseId'))=%s ORDER BY id",
            tuple(str(value) for value in user_ids) + (case_id,),
        )
        columns = [item[0] for item in cursor.description]
        return [dict(zip(columns, row)) for row in cursor.fetchall()]


def cleanup_l2(user_ids: list[int]) -> None:
    with mysql_connection(autocommit=False) as connection, connection.cursor() as cursor:
        for user_id in user_ids:
            cursor.execute("DELETE FROM ai_memory_slot WHERE owner_user_id=%s", (str(user_id),))
            cursor.execute("DELETE FROM ai_l2_memory_record WHERE user_id=%s", (str(user_id),))
        connection.commit()


def cleanup_graph(base: str, identities: dict[str, tuple[int, str]]) -> None:
    for _, token in identities.values():
        response = request_json(
            base, "/api/ai/memory-graph/debug/user", "DELETE", token=token, timeout=10.0,
        )
        if response.get("code") != 200:
            raise RuntimeError(f"graph cleanup failed: {response}")


def graph_extract(base: str, token: str, user_id: int, case: dict[str, Any]) -> list[dict[str, Any]]:
    content = "。".join(
        str(event.get("content") or "") for event in case.get("events", [])
        if event.get("shouldStore") and event.get("ownerAlias") == case.get("userAlias")
    )
    if not content or not case.get("expectedEdges"):
        return []
    response = request_json(base, "/api/ai/memory-graph/debug/extract", "POST", {
        "userId": str(user_id), "sessionId": case.get("sessionAlias", case["caseId"]),
        "content": content, "source": "memory_quality_eval",
    }, token, 20.0)
    if response.get("code") != 200:
        raise RuntimeError(f"graph extract failed: {response}")
    return (response.get("data") or {}).get("edges") or []


def edge_key(edge: dict[str, Any]) -> tuple[str, str, str]:
    return (str(edge.get("fromLabel") or edge.get("from") or "").lower(),
            str(edge.get("toLabel") or edge.get("to") or "").lower(),
            str(edge.get("edgeType") or edge.get("type") or "").lower())


def evaluate_case(base: str, case: dict[str, Any], identities: dict[str, tuple[int, str]]) -> dict[str, Any]:
    started = time.perf_counter()
    primary_alias = case["userAlias"]
    primary_id, primary_token = identities[primary_alias]
    mutations: list[dict[str, Any]] = []
    for event in case.get("events", []):
        owner = event.get("ownerAlias") or primary_alias
        _, token = identities[owner]
        response = mutate(base, token, event, case["caseId"])
        if response.get("code") != 200:
            raise RuntimeError(f"mutation failed {event['alias']}: {response}")
        mutations.append({"event": event, "result": response.get("data") or {}})

    rows = rows_for_case([value[0] for value in identities.values()], case["caseId"])
    id_alias = {}
    active_by_slot = {}
    persisted_content_aliases: dict[str, set[str]] = {}
    for row in rows:
        metadata = json.loads(row.get("metadata_json") or "{}")
        alias = metadata.get("alias", "")
        id_alias[int(row["id"])] = alias
        content = str(row.get("content") or "")
        if content and alias:
            persisted_content_aliases.setdefault(content, set()).add(alias)
        if row["status"] == "ACTIVE" and str(row["user_id"]) == str(primary_id):
            active_by_slot[row["slot_key"]] = row

    expected_store = {item["event"]["alias"] for item in mutations
                      if item["event"].get("operation") == "UPSERT" and item["event"].get("shouldStore")}
    actual_store = {item["event"]["alias"] for item in mutations
                    if item["result"].get("disposition") in ("CREATED", "UPDATED")}
    correct_store = expected_store & actual_store

    recalled = recall(base, primary_token, case)
    recalled_aliases = [id_alias.get(int(value), "") for value in recalled.get("adoptedMemoryIds") or []]
    gold = set(case.get("goldMemoryAliases") or [])
    forbidden = set(case.get("forbiddenMemoryAliases") or [])
    relevant_hits = [alias for alias in recalled_aliases if alias in gold]
    first_rank = next((index + 1 for index, alias in enumerate(recalled_aliases) if alias in gold), None)

    expected_values = case.get("expectedActiveValues") or {}
    active_values_ok = all(
        slot in active_by_slot and str(value) in str(active_by_slot[slot]["content"])
        for slot, value in expected_values.items()
    )
    active_slot_counts = Counter(
        (str(row["user_id"]), row["slot_key"]) for row in rows if row["status"] == "ACTIVE"
    )
    duplicate_active = sum(max(0, count - 1) for count in active_slot_counts.values())

    generated_edges = graph_extract(base, primary_token, primary_id, case)
    assembled = assemble_context(base, primary_token, case)
    assembled_audit = assembled.get("l2RecallAudit") or {}
    assembled_aliases = {
        id_alias.get(int(value), "")
        for value in assembled_audit.get("adoptedMemoryIds") or []
        if str(value).isdigit()
    }
    prompt_block = str(assembled.get("promptBlock") or "")
    prompt_aliases = {
        alias
        for content, aliases in persisted_content_aliases.items()
        if content in prompt_block
        for alias in aliases
    }
    context = case.get("context") or {}
    useful = set(context.get("usefulAliases") or []) & {event["alias"] for event in case.get("events", [])}
    context_hits = (assembled_aliases | prompt_aliases) - {""}
    context_correct = context_hits & useful
    context_forbidden = context_hits & set(context.get("forbiddenAliases") or [])
    actual_edges = {edge_key(item) for item in generated_edges}
    expected_edges = {edge_key(item) for item in case.get("expectedEdges") or []}
    forbidden_edges = {edge_key(item) for item in case.get("forbiddenEdges") or []}
    edge_correct = actual_edges & expected_edges

    return {
        "caseId": case["caseId"], "split": case["split"], "category": case["category"],
        "latencyMs": round((time.perf_counter() - started) * 1000, 2),
        "store": {"correct": len(correct_store), "actual": len(actual_store), "expected": len(expected_store),
                  "unexpected": sorted(actual_store - expected_store)},
        "recall": {"gold": len(gold), "hit": len(set(relevant_hits)), "firstRank": first_rank,
                   "forbiddenHits": sorted(set(recalled_aliases) & forbidden), "aliases": recalled_aliases,
                   "retrievalMode": recalled.get("retrievalMode"),
                   "fallbackReason": recalled.get("fallbackReason"),
                   "candidateTruncated": bool(recalled.get("candidateTruncated"))},
        "consolidation": {"duplicateActive": duplicate_active, "activeValuesOk": active_values_ok},
        "context": {"correct": len(context_correct), "actual": len(context_hits), "expected": len(useful),
                    "forbiddenHits": sorted(context_forbidden),
                    "aliases": sorted(context_hits),
                    "promptChars": int(assembled.get("promptChars") or 0),
                    "retrievalMode": assembled_audit.get("retrievalMode")},
        "graph": {"correct": len(edge_correct), "actual": len(actual_edges), "expected": len(expected_edges),
                  "forbiddenHits": len(actual_edges & forbidden_edges)},
    }


def ratio(numerator: int, denominator: int) -> float | None:
    return None if denominator == 0 else numerator / denominator


def aggregate(results: list[dict[str, Any]], enforce_thresholds: bool = True) -> dict[str, Any]:
    store_correct = sum(item["store"]["correct"] for item in results)
    store_actual = sum(item["store"]["actual"] for item in results)
    store_expected = sum(item["store"]["expected"] for item in results)
    recall_hit = sum(item["recall"]["hit"] for item in results)
    recall_gold = sum(item["recall"]["gold"] for item in results)
    mrr_values = [0.0 if item["recall"]["firstRank"] is None else 1.0 / item["recall"]["firstRank"]
                  for item in results if item["recall"]["gold"]]
    context_correct = sum(item["context"]["correct"] for item in results)
    context_actual = sum(item["context"]["actual"] for item in results)
    context_expected = sum(item["context"]["expected"] for item in results)
    graph_correct = sum(item["graph"]["correct"] for item in results)
    graph_actual = sum(item["graph"]["actual"] for item in results)
    graph_expected = sum(item["graph"]["expected"] for item in results)
    metrics = {
        "storePrecision": ratio(store_correct, store_actual), "storeRecall": ratio(store_correct, store_expected),
        "recallAt5": ratio(recall_hit, recall_gold), "mrr": statistics.mean(mrr_values) if mrr_values else None,
        "logicalDuplicates": sum(item["consolidation"]["duplicateActive"] for item in results),
        "activeValueFailures": sum(not item["consolidation"]["activeValuesOk"] for item in results),
        "contextPrecision": ratio(context_correct, context_actual),
        "contextCoverage": ratio(context_correct, context_expected),
        "edgePrecision": ratio(graph_correct, graph_actual), "edgeRecall": ratio(graph_correct, graph_expected),
        "forbiddenRecallHits": sum(len(item["recall"]["forbiddenHits"]) for item in results),
        "contextConflictHits": sum(len(item["context"]["forbiddenHits"]) for item in results),
        "forbiddenEdgeHits": sum(item["graph"]["forbiddenHits"] for item in results),
        "retrievalModes": dict(Counter(str(item["recall"].get("retrievalMode") or "UNKNOWN")
                                       for item in results)),
        "fallbackReasons": dict(Counter(str(item["recall"].get("fallbackReason") or "NONE")
                                        for item in results)),
        "candidateTruncatedCases": sum(bool(item["recall"].get("candidateTruncated"))
                                       for item in results),
    }
    threshold_passed = all([
        (metrics["storePrecision"] or 0) >= 0.95, (metrics["storeRecall"] or 0) >= 0.90,
        (metrics["recallAt5"] or 0) >= 0.90, (metrics["mrr"] or 0) >= 0.85,
        metrics["logicalDuplicates"] == 0, metrics["activeValueFailures"] == 0,
        (metrics["contextPrecision"] or 0) >= 0.85, (metrics["contextCoverage"] or 0) >= 0.90,
        (metrics["edgePrecision"] or 0) >= 0.90, (metrics["edgeRecall"] or 0) >= 0.80,
        metrics["forbiddenRecallHits"] == 0, metrics["contextConflictHits"] == 0,
        metrics["forbiddenEdgeHits"] == 0,
    ])
    metrics["passed"] = threshold_passed if enforce_thresholds else None
    return metrics


def hard_gate_failures(results: list[dict[str, Any]]) -> list[dict[str, str]]:
    failures: list[dict[str, str]] = []
    for item in results:
        category = item["category"]
        reasons: list[str] = []
        if item["recall"]["forbiddenHits"]:
            reasons.append("forbidden_memory_recalled")
        if item["context"]["forbiddenHits"]:
            reasons.append("context_conflict")
        if not item["consolidation"]["activeValuesOk"]:
            reasons.append("active_value_mismatch")
        if category in {"identity", "recovery"} and item["recall"]["hit"] < item["recall"]["gold"]:
            reasons.append("deterministic_recall_miss")
        if category == "identity" and item["graph"]["forbiddenHits"]:
            reasons.append("forbidden_graph_edge")
        if reasons:
            failures.append({"caseId": item["caseId"], "reason": ",".join(reasons)})
    return failures


def git_snapshot() -> dict[str, Any]:
    try:
        commit = subprocess.run(
            ["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True,
        ).stdout.strip()
        dirty = bool(subprocess.run(
            ["git", "status", "--porcelain"], check=True, capture_output=True, text=True,
        ).stdout.strip())
        return {"commit": commit, "dirty": dirty}
    except (OSError, subprocess.CalledProcessError):
        return {"commit": None, "dirty": None}


def write_markdown(path: Path, report: dict[str, Any]) -> None:
    metrics = report["metrics"]
    lines = [
        "# MEM-06 Five-layer Memory Evaluation",
        "",
        f"- Status: **{report['status']}**",
        f"- Requirement: `{REQUIREMENT_ID}`",
        f"- Dataset: `{report['dataset']}`",
        f"- Dataset SHA-256: `{report['datasetSha256']}`",
        f"- Cases: {report['executedCount']}/{report['caseCount']}",
        f"- Generated at: {report['generatedAt']}",
        "",
        "| Metric | Value |",
        "| --- | ---: |",
    ]
    for key in ("storePrecision", "storeRecall", "recallAt5", "mrr", "logicalDuplicates",
                "activeValueFailures", "contextPrecision", "contextCoverage", "edgePrecision",
                "edgeRecall", "forbiddenRecallHits", "contextConflictHits", "forbiddenEdgeHits"):
        value = metrics.get(key)
        rendered = "N/A" if value is None else (f"{value:.4f}" if isinstance(value, float) else str(value))
        lines.append(f"| {key} | {rendered} |")
    lines.extend(["", "## Hard Gate Failures", ""])
    failures = report.get("hardGateFailures") or []
    lines.extend([f"- `{item['caseId']}`: {item['reason']}" for item in failures] or ["- None"])
    lines.extend(["", "## Execution Errors", ""])
    errors = report.get("errors") or []
    lines.extend([f"- `{item.get('caseId')}`: {item.get('error')}" for item in errors] or ["- None"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()
    if not os.environ.get("MYSQL_PASSWORD"):
        os.environ["MYSQL_PASSWORD"] = "123456"
    dataset_path = Path(args.dataset)
    dataset_bytes = dataset_path.read_bytes()
    cases = json.loads(dataset_bytes.decode("utf-8"))
    if not isinstance(cases, list) or not cases:
        raise RuntimeError("dataset must be a non-empty JSON array")
    run_id = secrets.token_hex(5)
    user_a = create_synthetic_admin(args.base_url, "qa" + run_id)
    user_b = create_synthetic_admin(args.base_url, "qb" + run_id)
    identities = {"user-a": user_a, "user-b": user_b}
    results = []
    errors = []
    try:
        for case in cases:
            cleanup_l2([user_a[0], user_b[0]])
            cleanup_graph(args.base_url, identities)
            try:
                result = evaluate_case(args.base_url, case, identities)
                results.append(result)
                print(f"[CASE] {case['caseId']} latency={result['latencyMs']}")
            except Exception as error:  # noqa: BLE001
                errors.append({"caseId": case.get("caseId"), "error": f"{type(error).__name__}: {error}"})
                print(f"[ERROR] {case.get('caseId')} {error}")
            finally:
                cleanup_graph(args.base_url, identities)
        metrics = aggregate(results)
        hard_failures = hard_gate_failures(results)
        grouped = {
            category: aggregate([item for item in results if item["category"] == category], False)
            for category in sorted({item["category"] for item in results})
        }
        status = "PASS" if (not errors and not hard_failures and metrics["passed"]
                             and len(results) == len(cases)) else "FAIL"
        report = {"requirementId": REQUIREMENT_ID, "runId": run_id,
                  "dataset": args.dataset, "datasetSha256": hashlib.sha256(dataset_bytes).hexdigest(),
                  "git": git_snapshot(), "status": status,
                  "caseCount": len(cases), "executedCount": len(results), "metrics": metrics,
                  "categoryMetrics": grouped, "hardGateFailures": hard_failures,
                  "errors": errors, "results": results,
                  "generatedAt": datetime.now().astimezone().isoformat(timespec="seconds")}
    finally:
        cleanup_graph(args.base_url, identities)
        cleanup_l2([user_a[0], user_b[0]])
        with mysql_connection(autocommit=False) as connection, connection.cursor() as cursor:
            cursor.execute("DELETE FROM ums_user WHERE id IN (%s,%s)", (user_a[0], user_b[0]))
            connection.commit()
    args.report_dir.mkdir(parents=True, exist_ok=True)
    name = Path(args.dataset).stem.replace("_dataset", "")
    output = args.report_dir / f"{name}_report_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    write_markdown(output.with_suffix(".md"), report)
    print(json.dumps({"status": report["status"], "metrics": report["metrics"], "errors": errors}, ensure_ascii=False, indent=2))
    print(f"written to {output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
