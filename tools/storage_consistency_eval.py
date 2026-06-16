#!/usr/bin/env python3
"""
Evaluate DB / Elasticsearch / Milvus chunkId consistency.

Example:
  python tools/storage_consistency_eval.py ^
    --base-url http://localhost:8080 ^
    --sample-docs 20 ^
    --bearer-token <JWT>
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from eval_auth import add_auth_args, resolve_bearer_token


@dataclass
class DocConsistencyResult:
    doc_id: int
    ok: bool
    db_count: int
    es_count: int
    milvus_count: int
    missing_in_es: list[int]
    missing_in_milvus: list[int]
    extra_in_es: list[int]
    extra_in_milvus: list[int]


def get_json(url: str, timeout_sec: float, bearer_token: str) -> tuple[dict[str, Any], float]:
    headers = {}
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    req = urllib.request.Request(url, headers=headers, method="GET")
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            raw = resp.read().decode("utf-8")
    finally:
        latency_ms = (time.perf_counter() - start) * 1000
    return json.loads(raw), latency_ms


def as_int_list(value: Any) -> list[int]:
    if not isinstance(value, list):
        return []
    out: list[int] = []
    for item in value:
        try:
            out.append(int(item))
        except Exception:
            continue
    return out


def evaluate(base_url: str, doc_id: int | None, sample_docs: int, per_doc_limit: int, timeout_sec: float, bearer_token: str) -> tuple[list[DocConsistencyResult], dict[str, Any], float]:
    params = {
        "sampleDocs": sample_docs,
        "perDocLimit": per_doc_limit,
    }
    if doc_id:
        params["docId"] = doc_id
    url = base_url.rstrip("/") + "/api/ai/storage/debug/consistency?" + urllib.parse.urlencode(params)
    resp, latency_ms = get_json(url, timeout_sec, bearer_token)
    code = int(resp.get("code") or 0)
    if code != 200:
        raise RuntimeError(f"APP code={code}, message={resp.get('message')}")
    data = resp.get("data") if isinstance(resp.get("data"), dict) else {}
    results: list[DocConsistencyResult] = []
    for item in data.get("docs") or []:
        if not isinstance(item, dict):
            continue
        results.append(
            DocConsistencyResult(
                doc_id=int(item.get("docId") or 0),
                ok=bool(item.get("consistent", False)),
                db_count=int(item.get("dbCount") or 0),
                es_count=int(item.get("esCount") or 0),
                milvus_count=int(item.get("milvusCount") or 0),
                missing_in_es=as_int_list(item.get("missingInEs")),
                missing_in_milvus=as_int_list(item.get("missingInMilvus")),
                extra_in_es=as_int_list(item.get("extraInEs")),
                extra_in_milvus=as_int_list(item.get("extraInMilvus")),
            )
        )
    return results, data.get("summary") if isinstance(data.get("summary"), dict) else {}, latency_ms


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, int(round((pct / 100) * (len(ordered) - 1)))))
    return ordered[idx]


def preview_ids(ids: list[int], limit: int = 12) -> str:
    shown = ids[:limit]
    suffix = "" if len(ids) <= limit else f"...(+{len(ids) - limit})"
    return ",".join(str(x) for x in shown) + suffix


def write_report(results: list[DocConsistencyResult], summary: dict[str, Any], latency_ms: float, output: Path) -> None:
    total = len(results)
    ok_count = sum(1 for r in results if r.ok)
    latencies = [latency_ms] if latency_ms > 0 else []
    lines: list[str] = []
    lines.append("# Storage Consistency Evaluation Report")
    lines.append("")
    lines.append(f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Checked docs: {total}")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Success rate: {(ok_count / total if total else 0):.2%}")
    lines.append(f"- Consistent docs: {ok_count}")
    lines.append(f"- Inconsistent docs: {total - ok_count}")
    lines.append(f"- API latency ms: {latency_ms:.1f}")
    lines.append(f"- P50 latency ms: {statistics.median(latencies) if latencies else 0:.1f}")
    lines.append(f"- P95 latency ms: {percentile(latencies, 95):.1f}")
    if summary:
        lines.append(f"- Server pass rate: {float(summary.get('passRate') or 0):.2%}")
    lines.append("")
    lines.append("## Failed Or Risky Docs")
    lines.append("")
    lines.append("| DocId | OK | DB | ES | Milvus | Missing ES | Missing Milvus | Extra ES | Extra Milvus |")
    lines.append("|---:|---:|---:|---:|---:|---|---|---|---|")
    for r in results:
        if r.ok:
            continue
        lines.append(
            f"| {r.doc_id} | {str(r.ok).lower()} | {r.db_count} | {r.es_count} | {r.milvus_count} | "
            f"`{preview_ids(r.missing_in_es)}` | `{preview_ids(r.missing_in_milvus)}` | "
            f"`{preview_ids(r.extra_in_es)}` | `{preview_ids(r.extra_in_milvus)}` |"
        )
    lines.append("")
    lines.append("## Per Doc")
    lines.append("")
    lines.append("| DocId | OK | DB | ES | Milvus |")
    lines.append("|---:|---:|---:|---:|---:|")
    for r in results:
        lines.append(f"| {r.doc_id} | {str(r.ok).lower()} | {r.db_count} | {r.es_count} | {r.milvus_count} |")
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--doc-id", type=int, default=0)
    parser.add_argument("--sample-docs", type=int, default=20)
    parser.add_argument("--per-doc-limit", type=int, default=200)
    parser.add_argument("--timeout-sec", type=float, default=15.0)
    parser.add_argument("--output", default="")
    add_auth_args(parser)
    args = parser.parse_args()

    token = resolve_bearer_token(args, args.base_url, args.timeout_sec)
    results, summary, latency_ms = evaluate(
        args.base_url,
        args.doc_id if args.doc_id > 0 else None,
        args.sample_docs,
        args.per_doc_limit,
        args.timeout_sec,
        token,
    )
    output = Path(args.output) if args.output else Path(f"storage_consistency_eval_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md")
    write_report(results, summary, latency_ms, output)
    for result in results:
        status = "OK" if result.ok else "RISK"
        print(
            f"[{status}] docId={result.doc_id} db={result.db_count} es={result.es_count} milvus={result.milvus_count} "
            f"missingEs={len(result.missing_in_es)} missingMilvus={len(result.missing_in_milvus)}"
        )
    print(f"written to {output}")
    return 0 if all(r.ok for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
