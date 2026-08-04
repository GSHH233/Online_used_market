#!/usr/bin/env python3
"""
Evaluate retrieval quality for vector-only / keyword-only / hybrid modes.

Example:
  python tools/retrieval_eval.py ^
    --base-url http://localhost:8080 ^
    --dataset eval/datasets/retrieval_eval_dataset.json ^
    --seed-docs eval/datasets/retrieval_eval_seed_docs.json ^
    --use-seed-doc-ids
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

from eval_auth import add_auth_args, resolve_bearer_token


DEFAULT_MODES = ["vector-only", "keyword-only", "hybrid"]


@dataclass
class CaseResult:
    case_id: str
    mode: str
    hit: bool
    first_rank: int | None
    total: int
    latency_ms: float
    empty: bool
    duplicate_rate: float
    fallback: bool
    retrieval_mode: str
    debug_info: str
    returned_doc_ids: list[int]
    returned_chunk_ids: list[int]
    gold_doc_ids: list[int]
    gold_chunk_ids: list[int]
    error: str = ""


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def build_seed_doc_id_map(seed_docs_path: Path | None) -> dict[str, int]:
    if not seed_docs_path:
        return {}
    docs = load_json(seed_docs_path)
    out: dict[str, int] = {}
    for doc in docs:
        case_id = doc.get("caseId")
        expected_doc_id = doc.get("expectedDocId")
        if case_id and isinstance(expected_doc_id, int):
            out[case_id] = expected_doc_id
    return out


def post_json(url: str, payload: dict[str, Any], timeout_sec: float, bearer_token: str = "") -> tuple[dict[str, Any], float]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    req = urllib.request.Request(
        url,
        data=body,
        headers=headers,
        method="POST",
    )
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
        if isinstance(item, int):
            out.append(item)
        elif isinstance(item, str) and item.isdigit():
            out.append(int(item))
    return out


def parse_fallback(debug_info: str) -> bool:
    text = (debug_info or "").lower()
    return "fallback=true" in text


def duplicate_rate(hits: list[dict[str, Any]]) -> float:
    if not hits:
        return 0.0
    keys: list[str] = []
    for hit in hits:
        doc_id = hit.get("docId")
        chunk_id = hit.get("chunkId")
        snippet = (hit.get("snippet") or "").strip()
        if doc_id is not None and chunk_id is not None:
            keys.append(f"{doc_id}#{chunk_id}")
        elif snippet:
            keys.append(snippet)
        else:
            keys.append(json.dumps(hit, ensure_ascii=False, sort_keys=True))
    unique = len(set(keys))
    return 1 - unique / len(keys)


def evaluate_case(
    base_url: str,
    case: dict[str, Any],
    mode: str,
    top_k: int,
    timeout_sec: float,
    seed_doc_id_map: dict[str, int],
    use_seed_doc_ids: bool,
    bearer_token: str,
) -> CaseResult:
    case_id = str(case.get("caseId", "UNKNOWN"))
    gold_doc_ids = as_int_list(case.get("goldDocIds"))
    gold_chunk_ids = as_int_list(case.get("goldChunkIds"))
    if use_seed_doc_ids and case_id in seed_doc_id_map:
        gold_doc_ids = [seed_doc_id_map[case_id]]
        # Chunk ids are database-generated during reindex, so doc-level gold is
        # the stable signal when seed docs are imported through HTTP.
        gold_chunk_ids = []

    payload = {
        "question": case.get("question"),
        "topK": top_k,
        "mode": mode,
        "filter": case.get("filter"),
    }
    url = base_url.rstrip("/") + "/api/ai/knowledge/search"

    try:
        resp, latency_ms = post_json(url, payload, timeout_sec, bearer_token)
        data = resp.get("data") or {}
        hits = data.get("hits") or []
        if not isinstance(hits, list):
            hits = []

        returned_doc_ids = [x for x in (hit.get("docId") for hit in hits) if isinstance(x, int)]
        returned_chunk_ids = [x for x in (hit.get("chunkId") for hit in hits) if isinstance(x, int)]

        first_rank: int | None = None
        for idx, hit in enumerate(hits, start=1):
            doc_id = hit.get("docId")
            chunk_id = hit.get("chunkId")
            if (doc_id in gold_doc_ids) or (chunk_id in gold_chunk_ids):
                first_rank = idx
                break

        hit_success = first_rank is not None
        debug_info = str(data.get("debugInfo") or "")
        return CaseResult(
            case_id=case_id,
            mode=mode,
            hit=hit_success,
            first_rank=first_rank,
            total=int(data.get("total") or len(hits)),
            latency_ms=latency_ms,
            empty=len(hits) == 0,
            duplicate_rate=duplicate_rate(hits),
            fallback=parse_fallback(debug_info),
            retrieval_mode=str(data.get("retrievalMode") or ""),
            debug_info=debug_info,
            returned_doc_ids=returned_doc_ids,
            returned_chunk_ids=returned_chunk_ids,
            gold_doc_ids=gold_doc_ids,
            gold_chunk_ids=gold_chunk_ids,
        )
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
        return CaseResult(
            case_id=case_id,
            mode=mode,
            hit=False,
            first_rank=None,
            total=0,
            latency_ms=0,
            empty=True,
            duplicate_rate=0,
            fallback=False,
            retrieval_mode="",
            debug_info="",
            returned_doc_ids=[],
            returned_chunk_ids=[],
            gold_doc_ids=gold_doc_ids,
            gold_chunk_ids=gold_chunk_ids,
            error=str(exc),
        )


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    sorted_values = sorted(values)
    index = min(len(sorted_values) - 1, max(0, int(round((p / 100) * (len(sorted_values) - 1)))))
    return sorted_values[index]


def summarize(results: list[CaseResult], mode: str, k: int) -> dict[str, Any]:
    group = [r for r in results if r.mode == mode]
    total = len(group)
    if total == 0:
        return {}
    hits = [r for r in group if r.hit and (r.first_rank or 999999) <= k]
    reciprocal_ranks = [1 / r.first_rank for r in group if r.first_rank]
    latencies = [r.latency_ms for r in group if r.latency_ms > 0]
    return {
        "mode": mode,
        "cases": total,
        f"recall@{k}": len(hits) / total,
        "mrr": sum(reciprocal_ranks) / total,
        "empty_rate": sum(1 for r in group if r.empty) / total,
        "duplicate_rate_avg": sum(r.duplicate_rate for r in group) / total,
        "fallback_rate": sum(1 for r in group if r.fallback) / total,
        "errors": sum(1 for r in group if r.error),
        "p50_ms": statistics.median(latencies) if latencies else 0,
        "p95_ms": percentile(latencies, 95),
    }


def markdown_report(results: list[CaseResult], modes: list[str], top_k: int) -> str:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    lines: list[str] = []
    lines.append(f"# Retrieval Evaluation Report")
    lines.append("")
    lines.append(f"- Generated at: {now}")
    lines.append(f"- Modes: {', '.join(modes)}")
    lines.append(f"- TopK: {top_k}")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append("| Mode | Cases | Recall@K | MRR | Empty Rate | Duplicate Rate | Fallback Rate | P50 ms | P95 ms | Errors |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for mode in modes:
        s = summarize(results, mode, top_k)
        lines.append(
            f"| {mode} | {s['cases']} | {s[f'recall@{top_k}']:.2%} | {s['mrr']:.3f} | "
            f"{s['empty_rate']:.2%} | {s['duplicate_rate_avg']:.2%} | {s['fallback_rate']:.2%} | "
            f"{s['p50_ms']:.1f} | {s['p95_ms']:.1f} | {s['errors']} |"
        )
    lines.append("")
    lines.append("## Failed Or Empty Cases")
    lines.append("")
    lines.append("| Case | Mode | Gold DocIds | Returned DocIds | Total | Debug | Error |")
    lines.append("|---|---|---|---|---:|---|---|")
    for r in results:
        if r.hit and not r.empty and not r.error:
            continue
        debug = r.debug_info.replace("|", "\\|")[:180]
        error = r.error.replace("|", "\\|")[:120]
        lines.append(
            f"| {r.case_id} | {r.mode} | {r.gold_doc_ids} | {r.returned_doc_ids} | "
            f"{r.total} | `{debug}` | `{error}` |"
        )
    lines.append("")
    lines.append("## Per Case")
    lines.append("")
    lines.append("| Case | Mode | Hit | Rank | Gold DocIds | Returned DocIds | Latency ms | Debug |")
    lines.append("|---|---|---:|---:|---|---|---:|---|")
    for r in results:
        debug = r.debug_info.replace("|", "\\|")[:160]
        rank = r.first_rank if r.first_rank is not None else ""
        lines.append(
            f"| {r.case_id} | {r.mode} | {str(r.hit).lower()} | {rank} | {r.gold_doc_ids} | "
            f"{r.returned_doc_ids} | {r.latency_ms:.1f} | `{debug}` |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate retrieval modes.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/retrieval_eval_dataset.json")
    parser.add_argument("--seed-docs", default="eval/datasets/retrieval_eval_seed_docs.json")
    parser.add_argument("--use-seed-doc-ids", action="store_true")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--timeout-sec", type=float, default=30)
    parser.add_argument("--modes", nargs="+", default=DEFAULT_MODES)
    parser.add_argument("--out", default="")
    add_auth_args(parser)
    args = parser.parse_args()
    args.bearer_token = resolve_bearer_token(args, args.base_url, args.timeout_sec)

    dataset_path = Path(args.dataset)
    seed_docs_path = Path(args.seed_docs) if args.seed_docs else None
    cases = load_json(dataset_path)
    if not isinstance(cases, list):
        print("Dataset must be a JSON array.", file=sys.stderr)
        return 2

    seed_doc_id_map = build_seed_doc_id_map(seed_docs_path)
    results: list[CaseResult] = []
    for case in cases:
        for mode in args.modes:
            result = evaluate_case(
                args.base_url,
                case,
                mode,
                args.top_k,
                args.timeout_sec,
                seed_doc_id_map,
                args.use_seed_doc_ids,
                args.bearer_token,
            )
            results.append(result)
            status = "HIT" if result.hit else "MISS"
            print(f"{result.case_id:10s} {mode:12s} {status:4s} rank={result.first_rank} total={result.total} {result.latency_ms:.1f}ms")

    report = markdown_report(results, args.modes, args.top_k)
    if args.out:
        out_path = Path(args.out)
    else:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        out_path = Path(f"retrieval_eval_report_{stamp}.md")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(report, encoding="utf-8")
    print(f"\nReport written: {out_path}")

    for mode in args.modes:
        s = summarize(results, mode, args.top_k)
        print(
            f"{mode}: recall@{args.top_k}={s[f'recall@{args.top_k}']:.2%}, "
            f"mrr={s['mrr']:.3f}, empty={s['empty_rate']:.2%}, p95={s['p95_ms']:.1f}ms"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
