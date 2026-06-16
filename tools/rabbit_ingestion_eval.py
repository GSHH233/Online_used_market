#!/usr/bin/env python3
"""Evaluate the RabbitMQ-backed knowledge ingestion job."""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any

from eval_auth import add_auth_args, resolve_bearer_token


TERMINAL_STATUSES = {"SUCCESS", "FAILED", "DEAD_LETTERED"}


def request_json(url: str, method: str, timeout_sec: float, bearer_token: str) -> tuple[dict[str, Any], float]:
    headers = {}
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    request = urllib.request.Request(url, headers=headers, method=method)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        try:
            body = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            body = {}
        app_code = body.get("code") if isinstance(body, dict) else None
        message = body.get("message") if isinstance(body, dict) else None
        detail = f"APP code={app_code}, message={message}" if app_code or message else (raw or error.reason)
        if error.code == 401:
            detail += "; JWT 无效或已过期，请重新登录并传入 ADMIN/INTERNAL token"
        elif error.code == 403:
            detail += "; 当前 JWT 没有 ADMIN/INTERNAL 权限"
        raise RuntimeError(f"HTTP {error.code} {method} {url}: {detail}") from error
    return payload, (time.perf_counter() - started) * 1000


def app_data(response: dict[str, Any]) -> dict[str, Any]:
    if int(response.get("code") or 0) != 200:
        raise RuntimeError(f"APP code={response.get('code')}, message={response.get('message')}")
    data = response.get("data")
    return data if isinstance(data, dict) else {}


def submit(base_url: str, doc_id: int, timeout_sec: float, bearer_token: str) -> tuple[dict[str, Any], float]:
    query = urllib.parse.urlencode({"async": "true"})
    url = f"{base_url.rstrip('/')}/api/ai/knowledge/jobs/ingest/{doc_id}?{query}"
    response, latency_ms = request_json(url, "POST", timeout_sec, bearer_token)
    return app_data(response), latency_ms


def get_task(base_url: str, task_id: int, timeout_sec: float, bearer_token: str) -> dict[str, Any]:
    url = f"{base_url.rstrip('/')}/api/ai/knowledge/jobs/{task_id}"
    response, _ = request_json(url, "GET", timeout_sec, bearer_token)
    return app_data(response)


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(ratio * len(ordered)) - 1))
    return ordered[index]


def load_cases(dataset_path: str, doc_ids: list[int] | None) -> list[dict[str, Any]]:
    if dataset_path:
        payload = json.loads(Path(dataset_path).read_text(encoding="utf-8"))
        cases = payload.get("cases") if isinstance(payload, dict) else payload
        if not isinstance(cases, list):
            raise ValueError("dataset must be a JSON array or an object containing cases")
        normalized: list[dict[str, Any]] = []
        for index, case in enumerate(cases, start=1):
            if not isinstance(case, dict):
                raise ValueError(f"dataset case #{index} must be an object")
            doc_id = int(case.get("docId") or 0)
            if doc_id <= 0:
                raise ValueError(f"dataset case #{index} has invalid docId")
            normalized.append({
                "caseId": str(case.get("caseId") or f"RABBIT-INGEST-{index:03d}"),
                "description": str(case.get("description") or ""),
                "docId": doc_id,
                "expectedConsistent": bool(case.get("expectedConsistent", True)),
                "minChunkCount": max(0, int(case.get("minChunkCount") or 0)),
                "maxSubmitLatencyMs": float(case.get("maxSubmitLatencyMs") or 0),
                "maxJobElapsedMs": float(case.get("maxJobElapsedMs") or 0),
            })
        return normalized
    if not doc_ids:
        raise ValueError("provide --dataset or at least one --doc-id")
    return [
        {
            "caseId": f"RABBIT-INGEST-{index:03d}",
            "description": "",
            "docId": doc_id,
            "expectedConsistent": True,
            "minChunkCount": 1,
            "maxSubmitLatencyMs": 0,
            "maxJobElapsedMs": 0,
        }
        for index, doc_id in enumerate(doc_ids, start=1)
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/rabbit_ingestion_eval_dataset.json")
    parser.add_argument("--doc-id", type=int, action="append")
    parser.add_argument("--timeout-sec", type=float, default=15.0)
    parser.add_argument("--job-timeout-sec", type=float, default=120.0)
    parser.add_argument("--poll-interval-sec", type=float, default=1.0)
    parser.add_argument("--report-dir", default=".")
    add_auth_args(parser)
    args = parser.parse_args()

    token = resolve_bearer_token(args, args.base_url, args.timeout_sec)
    cases = load_cases(args.dataset, args.doc_id)
    rows: list[dict[str, Any]] = []

    for case in cases:
        case_id = str(case["caseId"])
        doc_id = int(case["docId"])
        submitted, submit_latency_ms = submit(args.base_url, doc_id, args.timeout_sec, token)
        task_id = int(submitted.get("taskId") or 0)
        if task_id <= 0:
            raise RuntimeError(f"docId={doc_id}: missing taskId in submit response")

        deadline = time.monotonic() + args.job_timeout_sec
        task: dict[str, Any] = {}
        observed_statuses: list[str] = []
        while time.monotonic() < deadline:
            task = get_task(args.base_url, task_id, args.timeout_sec, token)
            status = str(task.get("status") or "")
            if not observed_statuses or observed_statuses[-1] != status:
                observed_statuses.append(status)
            if status in TERMINAL_STATUSES:
                break
            time.sleep(max(0.1, args.poll_interval_sec))

        status = str(task.get("status") or "TIMEOUT")
        consistent = bool(task.get("consistent"))
        db_count = int(task.get("dbCount") or 0)
        es_count = int(task.get("esCount") or 0)
        milvus_count = int(task.get("milvusCount") or 0)
        job_elapsed_ms = int(task.get("elapsedMs") or 0)
        min_chunk_count = int(case["minChunkCount"])
        max_submit_latency_ms = float(case["maxSubmitLatencyMs"])
        max_job_elapsed_ms = float(case["maxJobElapsedMs"])
        failures: list[str] = []
        if status != "SUCCESS":
            failures.append(f"status:{status}")
        if consistent != bool(case["expectedConsistent"]):
            failures.append(f"consistent:{consistent}")
        if not (db_count == es_count == milvus_count):
            failures.append(f"countMismatch:{db_count}/{es_count}/{milvus_count}")
        if min(db_count, es_count, milvus_count) < min_chunk_count:
            failures.append(f"chunkCountBelowMin:{min(db_count, es_count, milvus_count)}<{min_chunk_count}")
        if task.get("asyncTask") is not True:
            failures.append("asyncTaskNotTrue")
        if str(task.get("taskType") or "") != "INGEST":
            failures.append(f"taskType:{task.get('taskType')}")
        if max_submit_latency_ms > 0 and submit_latency_ms > max_submit_latency_ms:
            failures.append(f"submitLatencyExceeded:{submit_latency_ms:.1f}>{max_submit_latency_ms:.1f}")
        if max_job_elapsed_ms > 0 and job_elapsed_ms > max_job_elapsed_ms:
            failures.append(f"jobLatencyExceeded:{job_elapsed_ms}>{max_job_elapsed_ms:.0f}")
        ok = not failures
        row = {
            "caseId": case_id,
            "docId": doc_id,
            "taskId": task_id,
            "ok": ok,
            "status": status,
            "observedStatuses": observed_statuses,
            "retryCount": int(task.get("retryCount") or 0),
            "submitLatencyMs": round(submit_latency_ms, 1),
            "jobElapsedMs": job_elapsed_ms,
            "dbCount": db_count,
            "esCount": es_count,
            "milvusCount": milvus_count,
            "failures": failures,
            "errorMsg": task.get("errorMsg") or "",
        }
        rows.append(row)
        print(
            f"[{'OK' if ok else 'FAIL'}] {case_id} docId={doc_id} taskId={task_id} status={status} "
            f"retry={row['retryCount']} submit={row['submitLatencyMs']}ms job={row['jobElapsedMs']}ms "
            f"path={'->'.join(observed_statuses)} details={failures}"
        )

    submit_latencies = [float(row["submitLatencyMs"]) for row in rows]
    job_latencies = [float(row["jobElapsedMs"]) for row in rows]
    success_count = sum(1 for row in rows if row["ok"])
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    report_path = Path(args.report_dir) / f"rabbit_ingestion_eval_report_{timestamp}.md"
    lines = [
        "# RabbitMQ Knowledge Ingestion Eval",
        "",
        f"- generatedAt: {datetime.now().isoformat(timespec='seconds')}",
        f"- total: {len(rows)}",
        f"- success: {success_count}",
        f"- successRate: {(success_count / len(rows) * 100) if rows else 0:.2f}%",
        f"- submitP50Ms: {percentile(submit_latencies, 0.50):.1f}",
        f"- submitP95Ms: {percentile(submit_latencies, 0.95):.1f}",
        f"- jobP50Ms: {percentile(job_latencies, 0.50):.1f}",
        f"- jobP95Ms: {percentile(job_latencies, 0.95):.1f}",
        "",
        "| case | docId | taskId | status | retry | submitMs | jobMs | DB/ES/Milvus | statusPath | details |",
        "|---|---:|---:|---|---:|---:|---:|---|---|---|",
    ]
    for row in rows:
        counts = f"{row['dbCount']}/{row['esCount']}/{row['milvusCount']}"
        lines.append(
            f"| {row['caseId']} | {row['docId']} | {row['taskId']} | {row['status']} | {row['retryCount']} | "
            f"{row['submitLatencyMs']:.1f} | {row['jobElapsedMs']} | {counts} | "
            f"{' -> '.join(row['observedStatuses'])} | {','.join(row['failures'])} |"
        )
        if row["errorMsg"]:
            lines.append(f"\n- taskId={row['taskId']} error: `{row['errorMsg']}`")
    lines.append("")
    lines.append("```json")
    lines.append(json.dumps(rows, ensure_ascii=False, indent=2))
    lines.append("```")
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"written to {report_path}")
    return 0 if success_count == len(rows) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"[ERROR] {error}", file=sys.stderr)
        raise SystemExit(2)
