#!/usr/bin/env python3
"""
Repair DB / Elasticsearch / Milvus chunk consistency by calling backend repair jobs.

Default mode repairs only inconsistent docs found by the consistency endpoint.
Use --force-reindex to force backend repair for every checked doc.

Example:
  python tools/storage_repair.py ^
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
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from eval_auth import add_auth_args, resolve_bearer_token


@dataclass
class DocState:
    doc_id: int
    ok: bool
    db_count: int
    es_count: int
    milvus_count: int
    missing_in_es: list[int]
    missing_in_milvus: list[int]
    extra_in_es: list[int]
    extra_in_milvus: list[int]


@dataclass
class RepairResult:
    doc_id: int
    attempted: bool
    reindex_ok: bool
    before_ok: bool
    after_ok: bool
    before_db: int
    before_es: int
    before_milvus: int
    after_db: int
    after_es: int
    after_milvus: int
    latency_ms: float
    message: str
    error: str


def request_json(method: str, url: str, payload: dict[str, Any] | None, timeout_sec: float, bearer_token: str) -> tuple[dict[str, Any], float]:
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as ex:
        raw = ex.read().decode("utf-8")
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


def fetch_consistency(base_url: str, doc_id: int | None, sample_docs: int, per_doc_limit: int, timeout_sec: float, bearer_token: str) -> tuple[list[DocState], float]:
    params: dict[str, Any] = {
        "sampleDocs": sample_docs,
        "perDocLimit": per_doc_limit,
    }
    if doc_id:
        params["docId"] = doc_id
    url = base_url.rstrip("/") + "/api/ai/storage/debug/consistency?" + urllib.parse.urlencode(params)
    resp, latency_ms = request_json("GET", url, None, timeout_sec, bearer_token)
    code = int(resp.get("code") or 0)
    if code != 200:
        raise RuntimeError(f"consistency APP code={code}, message={resp.get('message')}")
    data = resp.get("data") if isinstance(resp.get("data"), dict) else {}
    docs: list[DocState] = []
    for item in data.get("docs") or []:
        if not isinstance(item, dict):
            continue
        docs.append(
            DocState(
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
    return docs, latency_ms


def repair_doc(base_url: str, doc_id: int, timeout_sec: float, bearer_token: str, force_reindex: bool) -> tuple[bool, float, str, str]:
    query = urllib.parse.urlencode({"forceReindex": str(force_reindex).lower(), "async": "false"})
    url = base_url.rstrip("/") + f"/api/ai/knowledge/repair/{doc_id}?{query}"
    try:
        resp, latency_ms = request_json("POST", url, {}, timeout_sec, bearer_token)
        code = int(resp.get("code") or 0)
        message = str(resp.get("message") or "")
        if code != 200:
            return False, latency_ms, message, f"APP code={code}"
        data = resp.get("data")
        if isinstance(data, dict):
            message = str(data.get("message") or message)
        return True, latency_ms, message, ""
    except Exception as exc:
        return False, 0.0, "", str(exc)


def by_doc_id(states: list[DocState]) -> dict[int, DocState]:
    return {state.doc_id: state for state in states}


def choose_targets(states: list[DocState], force_reindex: bool) -> list[DocState]:
    if force_reindex:
        return [state for state in states if state.doc_id > 0]
    return [state for state in states if state.doc_id > 0 and not state.ok]


def repair(base_url: str, doc_id: int | None, sample_docs: int, per_doc_limit: int, timeout_sec: float, bearer_token: str, force_reindex: bool, dry_run: bool, sleep_sec: float) -> tuple[list[RepairResult], list[DocState], list[DocState], float, float]:
    before, before_latency = fetch_consistency(base_url, doc_id, sample_docs, per_doc_limit, timeout_sec, bearer_token)
    targets = choose_targets(before, force_reindex)
    before_map = by_doc_id(before)
    repair_results: list[RepairResult] = []

    for target in targets:
        if dry_run:
            repair_results.append(
                RepairResult(
                    doc_id=target.doc_id,
                    attempted=False,
                    reindex_ok=False,
                    before_ok=target.ok,
                    after_ok=target.ok,
                    before_db=target.db_count,
                    before_es=target.es_count,
                    before_milvus=target.milvus_count,
                    after_db=target.db_count,
                    after_es=target.es_count,
                    after_milvus=target.milvus_count,
                    latency_ms=0.0,
                    message="dry-run",
                    error="",
                )
            )
            continue
        ok, latency_ms, message, error = repair_doc(base_url, target.doc_id, timeout_sec, bearer_token, force_reindex)
        repair_results.append(
            RepairResult(
                doc_id=target.doc_id,
                attempted=True,
                reindex_ok=ok,
                before_ok=target.ok,
                after_ok=False,
                before_db=target.db_count,
                before_es=target.es_count,
                before_milvus=target.milvus_count,
                after_db=0,
                after_es=0,
                after_milvus=0,
                latency_ms=latency_ms,
                message=message,
                error=error,
            )
        )
        if sleep_sec > 0:
            time.sleep(sleep_sec)

    after, after_latency = fetch_consistency(base_url, doc_id, sample_docs, per_doc_limit, timeout_sec, bearer_token)
    after_map = by_doc_id(after)
    merged_results: list[RepairResult] = []
    existing = {r.doc_id: r for r in repair_results}

    for state in before:
        result = existing.get(state.doc_id)
        after_state = after_map.get(state.doc_id)
        if result is None:
            result = RepairResult(
                doc_id=state.doc_id,
                attempted=False,
                reindex_ok=False,
                before_ok=state.ok,
                after_ok=after_state.ok if after_state else False,
                before_db=state.db_count,
                before_es=state.es_count,
                before_milvus=state.milvus_count,
                after_db=after_state.db_count if after_state else 0,
                after_es=after_state.es_count if after_state else 0,
                after_milvus=after_state.milvus_count if after_state else 0,
                latency_ms=0.0,
                message="not-targeted",
                error="",
            )
        else:
            result.after_ok = after_state.ok if after_state else False
            result.after_db = after_state.db_count if after_state else 0
            result.after_es = after_state.es_count if after_state else 0
            result.after_milvus = after_state.milvus_count if after_state else 0
        merged_results.append(result)
    return merged_results, before, after, before_latency, after_latency


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, int(round((pct / 100) * (len(ordered) - 1)))))
    return ordered[idx]


def write_report(results: list[RepairResult], before: list[DocState], after: list[DocState], before_latency: float, after_latency: float, output: Path, force_reindex: bool, dry_run: bool) -> None:
    attempted = [r for r in results if r.attempted]
    reindex_latencies = [r.latency_ms for r in attempted if r.latency_ms > 0]
    before_bad = sum(1 for state in before if not state.ok)
    after_bad = sum(1 for state in after if not state.ok)
    lines: list[str] = []
    lines.append("# Storage Repair Report")
    lines.append("")
    lines.append(f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Mode: {'force-reindex' if force_reindex else 'repair-inconsistent'}")
    lines.append(f"- Dry run: {str(dry_run).lower()}")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Checked docs: {len(before)}")
    lines.append(f"- Before inconsistent docs: {before_bad}")
    lines.append(f"- Target docs: {len(attempted) if not dry_run else sum(1 for r in results if r.message == 'dry-run')}")
    lines.append(f"- Reindex success docs: {sum(1 for r in attempted if r.reindex_ok)}")
    lines.append(f"- Reindex failed docs: {sum(1 for r in attempted if not r.reindex_ok)}")
    lines.append(f"- After inconsistent docs: {after_bad}")
    lines.append(f"- Before consistency latency ms: {before_latency:.1f}")
    lines.append(f"- After consistency latency ms: {after_latency:.1f}")
    lines.append(f"- Reindex P50 latency ms: {statistics.median(reindex_latencies) if reindex_latencies else 0:.1f}")
    lines.append(f"- Reindex P95 latency ms: {percentile(reindex_latencies, 95):.1f}")
    lines.append("")
    lines.append("## Failed Or Still Risky Docs")
    lines.append("")
    lines.append("| DocId | Attempted | ReindexOK | BeforeOK | AfterOK | Before DB/ES/Milvus | After DB/ES/Milvus | Error |")
    lines.append("|---:|---:|---:|---:|---:|---|---|---|")
    for r in results:
        if r.after_ok and (not r.attempted or r.reindex_ok):
            continue
        lines.append(
            f"| {r.doc_id} | {str(r.attempted).lower()} | {str(r.reindex_ok).lower()} | "
            f"{str(r.before_ok).lower()} | {str(r.after_ok).lower()} | "
            f"{r.before_db}/{r.before_es}/{r.before_milvus} | {r.after_db}/{r.after_es}/{r.after_milvus} | `{r.error}` |"
        )
    lines.append("")
    lines.append("## Per Doc")
    lines.append("")
    lines.append("| DocId | Attempted | ReindexOK | BeforeOK | AfterOK | Reindex ms | Message |")
    lines.append("|---:|---:|---:|---:|---:|---:|---|")
    for r in results:
        lines.append(
            f"| {r.doc_id} | {str(r.attempted).lower()} | {str(r.reindex_ok).lower()} | "
            f"{str(r.before_ok).lower()} | {str(r.after_ok).lower()} | {r.latency_ms:.1f} | `{r.message}` |"
        )
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--doc-id", type=int, default=0)
    parser.add_argument("--sample-docs", type=int, default=20)
    parser.add_argument("--per-doc-limit", type=int, default=200)
    parser.add_argument("--timeout-sec", type=float, default=60.0)
    parser.add_argument("--sleep-sec", type=float, default=0.2)
    parser.add_argument("--force-reindex", action="store_true", help="Reindex all checked docs, not only inconsistent docs.")
    parser.add_argument("--dry-run", action="store_true", help="Only report target docs without calling reindex.")
    parser.add_argument("--output", default="")
    add_auth_args(parser)
    args = parser.parse_args()

    token = resolve_bearer_token(args, args.base_url, args.timeout_sec)
    results, before, after, before_latency, after_latency = repair(
        args.base_url,
        args.doc_id if args.doc_id > 0 else None,
        args.sample_docs,
        args.per_doc_limit,
        args.timeout_sec,
        token,
        args.force_reindex,
        args.dry_run,
        args.sleep_sec,
    )
    output = Path(args.output) if args.output else Path(f"storage_repair_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md")
    write_report(results, before, after, before_latency, after_latency, output, args.force_reindex, args.dry_run)
    for result in results:
        if not result.attempted and result.message != "dry-run" and result.after_ok:
            continue
        status = "OK" if result.after_ok else "RISK"
        print(
            f"[{status}] docId={result.doc_id} attempted={result.attempted} reindex={result.reindex_ok} "
            f"before={result.before_db}/{result.before_es}/{result.before_milvus} "
            f"after={result.after_db}/{result.after_es}/{result.after_milvus} error={result.error}"
        )
    print(f"written to {output}")
    return 0 if all(r.after_ok for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
