#!/usr/bin/env python3
"""
Evaluate final RAG Chat answers through the HTTP eval endpoint.

Example:
  python tools/rag_chat_eval.py ^
    --base-url http://localhost:8080 ^
    --dataset eval/datasets/retrieval_eval_hard_dataset.json ^
    --bearer-token <JWT>
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


@dataclass
class ChatCaseResult:
    case_id: str
    gold_hit: bool
    keyword_hit_rate: float
    should_reject: bool
    low_confidence: bool
    reject_ok: bool
    sources_count: int
    latency_ms: float
    confidence_level: str
    answer: str
    retrieval_debug: str
    confidence_debug: str
    returned_doc_ids: list[int]
    gold_doc_ids: list[int]
    error: str = ""


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


def keyword_hit_rate(answer: str, keywords: list[str]) -> float:
    if not keywords:
        return 1.0
    text = (answer or "").lower()
    hits = 0
    for keyword in keywords:
        if str(keyword).lower() in text:
            hits += 1
    return hits / len(keywords)


def evaluate_case(base_url: str, case: dict[str, Any], top_k: int, timeout_sec: float, bearer_token: str) -> ChatCaseResult:
    case_id = str(case.get("caseId", "UNKNOWN"))
    gold_doc_ids = as_int_list(case.get("goldDocIds"))
    expected_keywords = [str(x) for x in case.get("expectedAnswerKeywords", [])]
    should_reject = bool(case.get("shouldReject", False))
    payload = {
        "type": "CHAT_START",
        "requestId": case_id,
        "question": case.get("question"),
        "topK": top_k,
        "filter": case.get("filter"),
    }
    url = base_url.rstrip("/") + "/api/ai/rag-chat/eval"
    try:
        resp, latency_ms = post_json(url, payload, timeout_sec, bearer_token)
        app_code = resp.get("code")
        app_message = str(resp.get("message") or "")
        if app_code != 200:
            return ChatCaseResult(
                case_id=case_id,
                gold_hit=False,
                keyword_hit_rate=0.0,
                should_reject=should_reject,
                low_confidence=False,
                reject_ok=False,
                sources_count=0,
                latency_ms=latency_ms,
                confidence_level="",
                answer="",
                retrieval_debug="",
                confidence_debug="",
                returned_doc_ids=[],
                gold_doc_ids=gold_doc_ids,
                error=f"APP code={app_code}, message={app_message}",
            )
        data = resp.get("data") or {}
        if not data:
            return ChatCaseResult(
                case_id=case_id,
                gold_hit=False,
                keyword_hit_rate=0.0,
                should_reject=should_reject,
                low_confidence=False,
                reject_ok=False,
                sources_count=0,
                latency_ms=latency_ms,
                confidence_level="",
                answer="",
                retrieval_debug="",
                confidence_debug="",
                returned_doc_ids=[],
                gold_doc_ids=gold_doc_ids,
                error=f"EMPTY data, APP code={app_code}, message={app_message}",
            )
        answer = str(data.get("answer") or "")
        sources = data.get("sources") or []
        if not isinstance(sources, list):
            sources = []
        returned_doc_ids = [x for x in (source.get("docId") for source in sources) if isinstance(x, int)]
        gold_hit = any(doc_id in gold_doc_ids for doc_id in returned_doc_ids)
        low_confidence = bool(data.get("lowConfidence", False))
        # For ambiguous samples, acceptable behavior is either explicit low confidence
        # or an answer asking for more information.
        reject_ok = True
        if should_reject:
            answer_lower = answer.lower()
            reject_ok = low_confidence or any(term in answer_lower for term in ["补充", "无法", "不确定", "不能直接判断"])
        return ChatCaseResult(
            case_id=case_id,
            gold_hit=gold_hit,
            keyword_hit_rate=keyword_hit_rate(answer, expected_keywords),
            should_reject=should_reject,
            low_confidence=low_confidence,
            reject_ok=reject_ok,
            sources_count=len(sources),
            latency_ms=latency_ms,
            confidence_level=str(data.get("confidenceLevel") or ""),
            answer=answer,
            retrieval_debug=str(data.get("retrievalDebug") or ""),
            confidence_debug=str(data.get("confidenceDebug") or ""),
            returned_doc_ids=returned_doc_ids,
            gold_doc_ids=gold_doc_ids,
        )
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
        return ChatCaseResult(
            case_id=case_id,
            gold_hit=False,
            keyword_hit_rate=0.0,
            should_reject=should_reject,
            low_confidence=False,
            reject_ok=False,
            sources_count=0,
            latency_ms=0,
            confidence_level="",
            answer="",
            retrieval_debug="",
            confidence_debug="",
            returned_doc_ids=[],
            gold_doc_ids=gold_doc_ids,
            error=str(exc),
        )


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    sorted_values = sorted(values)
    index = min(len(sorted_values) - 1, max(0, int(round((p / 100) * (len(sorted_values) - 1)))))
    return sorted_values[index]


def markdown_report(results: list[ChatCaseResult], top_k: int) -> str:
    total = len(results)
    latencies = [r.latency_ms for r in results if r.latency_ms > 0]
    lines: list[str] = []
    lines.append("# RAG Chat Evaluation Report")
    lines.append("")
    lines.append(f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Cases: {total}")
    lines.append(f"- TopK: {top_k}")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Source gold hit rate: {sum(1 for r in results if r.gold_hit) / total:.2%}")
    lines.append(f"- Answer keyword hit rate avg: {sum(r.keyword_hit_rate for r in results) / total:.2%}")
    lines.append(f"- Reject accuracy: {sum(1 for r in results if r.reject_ok) / total:.2%}")
    lines.append(f"- Empty sources rate: {sum(1 for r in results if r.sources_count == 0) / total:.2%}")
    lines.append(f"- Low confidence rate: {sum(1 for r in results if r.low_confidence) / total:.2%}")
    lines.append(f"- Errors: {sum(1 for r in results if r.error)}")
    lines.append(f"- P50 latency ms: {statistics.median(latencies) if latencies else 0:.1f}")
    lines.append(f"- P95 latency ms: {percentile(latencies, 95):.1f}")
    lines.append("")
    lines.append("## Failed Or Risky Cases")
    lines.append("")
    lines.append("| Case | Gold Hit | Keyword Rate | Reject OK | Sources | Low Confidence | Gold DocIds | Returned DocIds | Error |")
    lines.append("|---|---:|---:|---:|---:|---:|---|---|---|")
    for r in results:
        if r.gold_hit and r.keyword_hit_rate >= 0.5 and r.reject_ok and not r.error:
            continue
        lines.append(
            f"| {r.case_id} | {str(r.gold_hit).lower()} | {r.keyword_hit_rate:.2%} | "
            f"{str(r.reject_ok).lower()} | {r.sources_count} | {str(r.low_confidence).lower()} | "
            f"{r.gold_doc_ids} | {r.returned_doc_ids} | `{r.error[:120]}` |"
        )
    lines.append("")
    lines.append("## Per Case")
    lines.append("")
    lines.append("| Case | Gold Hit | Keyword Rate | Reject OK | Confidence | Sources | Latency ms | Returned DocIds | Answer |")
    lines.append("|---|---:|---:|---:|---|---:|---:|---|---|")
    for r in results:
        answer = r.answer.replace("|", "\\|").replace("\n", " ")[:180]
        lines.append(
            f"| {r.case_id} | {str(r.gold_hit).lower()} | {r.keyword_hit_rate:.2%} | "
            f"{str(r.reject_ok).lower()} | {r.confidence_level} | {r.sources_count} | "
            f"{r.latency_ms:.1f} | {r.returned_doc_ids} | {answer} |"
        )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate final RAG Chat answers.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/retrieval_eval_hard_dataset.json")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--timeout-sec", type=float, default=90)
    add_auth_args(parser)
    parser.add_argument("--out", default="")
    args = parser.parse_args()
    args.bearer_token = resolve_bearer_token(args, args.base_url, args.timeout_sec)

    cases = load_json(Path(args.dataset))
    if not isinstance(cases, list):
        print("Dataset must be a JSON array.", file=sys.stderr)
        return 2

    results: list[ChatCaseResult] = []
    for case in cases:
        result = evaluate_case(args.base_url, case, args.top_k, args.timeout_sec, args.bearer_token)
        results.append(result)
        status = "OK" if result.gold_hit and result.reject_ok and result.keyword_hit_rate >= 0.5 and not result.error else "RISK"
        print(
            f"{result.case_id:15s} {status:4s} gold={result.gold_hit} "
            f"kw={result.keyword_hit_rate:.0%} reject={result.reject_ok} "
            f"sources={result.sources_count} {result.latency_ms:.1f}ms"
        )

    report = markdown_report(results, args.top_k)
    if args.out:
        out_path = Path(args.out)
    else:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        out_path = Path(f"rag_chat_eval_report_{stamp}.md")
    out_path.write_text(report, encoding="utf-8")
    print(f"\nReport written: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
