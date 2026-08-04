#!/usr/bin/env python3
"""
Evaluate L3 KV session summary, L2 semantic recall, and memory injection priority.

Example:
  python tools/memory_priority_eval.py ^
    --base-url http://localhost:8080 ^
    --dataset eval/datasets/memory_priority_eval_dataset.json ^
    --bearer-token <JWT>
"""

from __future__ import annotations

import argparse
import math
import json
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from eval_auth import add_auth_args, resolve_bearer_token


EPS = 1e-6


@dataclass
class MemoryCaseResult:
    case_id: str
    layer: str
    ok: bool
    latency_ms: float
    details: list[str]
    metrics: list[str]
    answer_preview: str


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def request_json(url: str, method: str, payload: dict[str, Any] | None, timeout_sec: float, bearer_token: str) -> tuple[dict[str, Any], float]:
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    start = time.perf_counter()
    with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
        raw = resp.read().decode("utf-8")
    return json.loads(raw), (time.perf_counter() - start) * 1000


def scoped_id(case_id: str, run_id: str) -> str:
    return f"{case_id}-{run_id}"[:48]


def write_l2(base_url: str, case_id: str, run_id: str, item: dict[str, Any], timeout_sec: float, bearer_token: str) -> None:
    sid = scoped_id(case_id, run_id)
    params = {
        "userId": f"eval-{sid}",
        "sessionId": f"eval-{sid}",
        "memoryType": item.get("memoryType", "PREFERENCE"),
        "content": item.get("content", ""),
        "importance": item.get("importance", 0.3),
    }
    if item.get("metadataJson"):
        params["metadataJson"] = item.get("metadataJson")
    url = base_url.rstrip("/") + "/api/ai/l2-memory/debug/write?" + urllib.parse.urlencode(params)
    request_json(url, "POST", None, timeout_sec, bearer_token)


def query_l2(base_url: str, case_id: str, run_id: str, query: dict[str, Any], timeout_sec: float, bearer_token: str) -> tuple[list[dict[str, Any]], str, float]:
    sid = scoped_id(case_id, run_id)
    params = {
        "userId": f"eval-{sid}",
        "sessionId": f"eval-{sid}",
        "q": query.get("q", ""),
        "topK": query.get("topK", 5),
        "threshold": query.get("threshold", 0.05),
    }
    url = base_url.rstrip("/") + "/api/ai/l2-memory/debug/query?" + urllib.parse.urlencode(params)
    resp, latency = request_json(url, "GET", None, timeout_sec, bearer_token)
    data = resp.get("data") if isinstance(resp.get("data"), list) else []
    return data, json.dumps(data or resp, ensure_ascii=False), latency


def chat_turn(base_url: str, case_id: str, run_id: str, turn: dict[str, Any], timeout_sec: float, bearer_token: str) -> tuple[str, float, dict[str, Any]]:
    payload = {
        "type": "CHAT_START",
        "requestId": scoped_id(case_id, run_id),
        "question": turn.get("question", ""),
        "topK": turn.get("topK", 5),
        "filter": turn.get("filter"),
    }
    url = base_url.rstrip("/") + "/api/ai/rag-chat/eval"
    resp, latency = request_json(url, "POST", payload, timeout_sec, bearer_token)
    data = resp.get("data") or {}
    return str(data.get("answer") or ""), latency, data


def extract_memory_graph(base_url: str, case_id: str, run_id: str, spec: dict[str, Any], timeout_sec: float, bearer_token: str) -> tuple[list[dict[str, Any]], str, float]:
    sid = scoped_id(case_id, run_id)
    payload = {
        "userId": f"eval-{sid}",
        "sessionId": f"eval-{sid}",
        "content": spec.get("content", ""),
        "source": spec.get("source", "memory_priority_eval"),
    }
    url = base_url.rstrip("/") + "/api/ai/memory-graph/debug/extract"
    resp, latency = request_json(url, "POST", payload, timeout_sec, bearer_token)
    data = resp.get("data") if isinstance(resp.get("data"), dict) else {}
    edges = data.get("edges") if isinstance(data.get("edges"), list) else []
    return edges, json.dumps(data or resp, ensure_ascii=False), latency


def contains_all(text: str, keywords: list[str]) -> list[str]:
    lower = text.lower()
    return [kw for kw in keywords if kw.lower() not in lower]


def contains_any(text: str, keywords: list[str]) -> list[str]:
    lower = text.lower()
    return [kw for kw in keywords if kw.lower() in lower]


def check_l2_query(case_id: str, label: str, hits: list[dict[str, Any]], text: str, spec: dict[str, Any], details: list[str]) -> None:
    missing = contains_all(text, [str(x) for x in spec.get("expectedKeywords", [])])
    forbidden = contains_any(text, [str(x) for x in spec.get("forbidKeywords", [])])
    if missing:
        details.append(f"{label}.l2MissingKeywords:" + ",".join(missing))
    if forbidden:
        details.append(f"{label}.l2ForbiddenKeywords:" + ",".join(forbidden))

    returned_types = {str(hit.get("memoryType") or "").upper() for hit in hits if isinstance(hit, dict)}
    expected_types = {str(x).upper() for x in spec.get("expectedMemoryTypes", [])}
    forbidden_types = {str(x).upper() for x in spec.get("forbidMemoryTypes", [])}
    missing_types = sorted(expected_types - returned_types)
    bad_types = sorted(returned_types & forbidden_types)
    if missing_types:
        details.append(f"{label}.missingMemoryTypes:" + ",".join(missing_types))
    if bad_types:
        details.append(f"{label}.forbiddenMemoryTypes:" + ",".join(bad_types))

    if "maxHits" in spec and len(hits) > int(spec["maxHits"]):
        details.append(f"{label}.tooManyHits:{len(hits)}>{spec['maxHits']}")
    if "minHits" in spec and len(hits) < int(spec["minHits"]):
        details.append(f"{label}.tooFewHits:{len(hits)}<{spec['minHits']}")


TOPIC_KEYWORDS: dict[str, list[str]] = {
    "phone": ["手机", "iphone", "串号", "imei", "全新未拆"],
    "gpu": ["显卡", "rtx", "3060", "矿卡", "跑分", "温度"],
    "camera": ["相机", "镜头", "快门", "传感器"],
    "drone": ["无人机", "遥控器", "桨叶", "电池"],
    "luxury": ["奢侈品", "专柜", "假一赔十", "正品"],
    "price": ["价格", "定价", "估价", "报价", "下调"],
    "risk": ["风险", "纠纷", "违规", "禁售", "不能发布"],
    "writing_style": ["详细", "保守", "如实", "文案", "标题"],
    "transaction": ["当面验货", "平台交易", "快递", "面交"],
    "evidence": ["发票", "凭证", "截图", "实拍", "检测"],
}


def hit_text(hit: dict[str, Any]) -> str:
    return " ".join(str(hit.get(k) or "") for k in ("memoryType", "content", "metadataJson"))


def normalize_memory_text(text: str) -> str:
    return "".join(text.lower().split())


def duplicate_rate(hits: list[dict[str, Any]]) -> float:
    if not hits:
        return 0.0
    keys = [normalize_memory_text(hit_text(hit)) for hit in hits]
    unique = len(set(keys))
    return (len(keys) - unique) / len(keys)


def topic_counts(hits: list[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for hit in hits:
        text = hit_text(hit).lower()
        for topic, keywords in TOPIC_KEYWORDS.items():
            if any(keyword.lower() in text for keyword in keywords):
                counts[topic] = counts.get(topic, 0) + 1
    return counts


def normalized_entropy(counts: dict[str, int]) -> float:
    total = sum(counts.values())
    if total <= 0 or len(counts) <= 1:
        return 0.0
    entropy = 0.0
    for count in counts.values():
        p = count / total
        entropy -= p * math.log(p)
    return entropy / math.log(len(counts))


def expected_entropy(topics: list[str]) -> float:
    if not topics or len(set(topics)) <= 1:
        return 0.0
    return 1.0


def first_relevant_rank(hits: list[dict[str, Any]], keywords: list[str]) -> int | None:
    if not keywords:
        return None
    for index, hit in enumerate(hits, start=1):
        text = hit_text(hit).lower()
        if all(keyword.lower() in text for keyword in keywords):
            return index
    return None


def keyword_coverage(text: str, keywords: list[str]) -> float:
    if not keywords:
        return 1.0
    lower = text.lower()
    hit_count = sum(1 for keyword in keywords if keyword.lower() in lower)
    return hit_count / len(keywords)


def parse_json_object(raw: str) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}


def memory_context_units(data: dict[str, Any]) -> list[str]:
    debug = parse_json_object(str(data.get("memoryAssemblyDebug") or ""))
    units: list[str] = []
    l3 = str(debug.get("l3Summary") or "").strip()
    if l3 and l3 != "（无）":
        units.append("L3:" + l3)
    profile = debug.get("historicalPreferences")
    if isinstance(profile, dict):
        for key, value in profile.items():
            units.append(f"PROFILE:{key}={value}")
    l3_autofill = debug.get("l3AutofillParams")
    if isinstance(l3_autofill, dict):
        for key, value in l3_autofill.items():
            units.append(f"L3_AUTOFILL:{key}={value}")
    for item in debug.get("l2InjectedMemories") or []:
        units.append("L2:" + str(item))
    for item in debug.get("recentConversation") or []:
        units.append("RECENT:" + str(item))
    return [unit for unit in units if unit.strip()]


def any_keyword(text: str, keywords: list[str]) -> bool:
    lower = text.lower()
    return any(keyword.lower() in lower for keyword in keywords)


def check_context_assembly(label: str, data: dict[str, Any], spec: dict[str, Any], details: list[str], metrics: list[str]) -> None:
    prompt = str(data.get("promptDebug") or "")
    units = memory_context_units(data)
    relevant_keywords = [str(x) for x in spec.get("relevantKeywords", [])]
    noise_keywords = [str(x) for x in spec.get("noiseKeywords", [])]

    relevant_units = [unit for unit in units if any_keyword(unit, relevant_keywords)]
    noise_units = [unit for unit in units if any_keyword(unit, noise_keywords)]
    total = len(units)
    precision = len(relevant_units) / total if total else 0.0
    noise_rate = len(noise_units) / total if total else 0.0
    prompt_coverage = keyword_coverage(prompt, [str(x) for x in spec.get("promptExpectedKeywords", [])])

    metrics.append(
        f"{label}:contextUnits={total},contextPrecision={precision:.2f},noiseRate={noise_rate:.2f},promptCoverage={prompt_coverage:.2f}"
    )
    if "minContextPrecision" in spec and precision + EPS < float(spec["minContextPrecision"]):
        details.append(f"{label}.contextPrecisionTooLow:{precision:.2f}<{spec['minContextPrecision']}")
    if "maxNoiseRate" in spec and noise_rate > float(spec["maxNoiseRate"]) + EPS:
        details.append(f"{label}.noiseRateTooHigh:{noise_rate:.2f}>{spec['maxNoiseRate']}")
    if "minPromptKeywordCoverage" in spec and prompt_coverage + EPS < float(spec["minPromptKeywordCoverage"]):
        details.append(f"{label}.promptCoverageTooLow:{prompt_coverage:.2f}<{spec['minPromptKeywordCoverage']}")
    if spec.get("requirePromptDebug", False) and not prompt:
        details.append(f"{label}.missingPromptDebug")


def edge_key(edge: dict[str, Any]) -> str:
    return normalize_memory_text(str(edge.get("from", ""))) + "->" + normalize_memory_text(str(edge.get("to", "")))


def edge_aliases(edge: dict[str, Any], field: str) -> list[str]:
    value = edge.get(field)
    if isinstance(value, list):
        return [str(x) for x in value if str(x).strip()]
    base = "from" if field == "fromAliases" else "to"
    text = str(edge.get(base, ""))
    return [text] if text.strip() else []


def edge_present(text: str, edge: dict[str, Any]) -> bool:
    lower = text.lower()
    from_hit = any(alias.lower() in lower for alias in edge_aliases(edge, "fromAliases"))
    to_hit = any(alias.lower() in lower for alias in edge_aliases(edge, "toAliases"))
    return from_hit and to_hit


def check_graph_edges(label: str, answer: str, data: dict[str, Any], spec: dict[str, Any], details: list[str], metrics: list[str]) -> None:
    text = answer
    if spec.get("includePromptDebug", False):
        text += "\n" + str(data.get("promptDebug") or "")
    if spec.get("includeMemoryAssemblyDebug", False):
        text += "\n" + str(data.get("memoryAssemblyDebug") or "")

    expected_edges = [edge for edge in spec.get("expectedEdges", []) if isinstance(edge, dict)]
    forbidden_edges = [edge for edge in spec.get("forbiddenEdges", []) if isinstance(edge, dict)]
    optional_edges = [edge for edge in spec.get("optionalEdges", []) if isinstance(edge, dict)]

    generated_expected = {edge_key(edge) for edge in expected_edges if edge_present(text, edge)}
    generated_forbidden = {edge_key(edge) for edge in forbidden_edges if edge_present(text, edge)}
    generated_optional = {edge_key(edge) for edge in optional_edges if edge_present(text, edge)}
    generated_total = len(generated_expected) + len(generated_forbidden) + len(generated_optional)
    expected_total = len({edge_key(edge) for edge in expected_edges})

    edge_precision = (len(generated_expected) / generated_total) if generated_total else 0.0
    edge_recall = (len(generated_expected) / expected_total) if expected_total else 1.0
    edge_f1 = 0.0 if edge_precision + edge_recall == 0 else 2 * edge_precision * edge_recall / (edge_precision + edge_recall)
    forbidden_rate = (len(generated_forbidden) / generated_total) if generated_total else 0.0

    metrics.append(
        f"{label}:edgePrecision={edge_precision:.2f},edgeRecall={edge_recall:.2f},edgeF1={edge_f1:.2f},forbiddenEdgeRate={forbidden_rate:.2f},correctEdges={len(generated_expected)},forbiddenEdges={len(generated_forbidden)},optionalEdges={len(generated_optional)},generatedEdges={generated_total}"
    )
    if "minEdgePrecision" in spec and edge_precision + EPS < float(spec["minEdgePrecision"]):
        details.append(f"{label}.edgePrecisionTooLow:{edge_precision:.2f}<{spec['minEdgePrecision']}")
    if "minEdgeRecall" in spec and edge_recall + EPS < float(spec["minEdgeRecall"]):
        details.append(f"{label}.edgeRecallTooLow:{edge_recall:.2f}<{spec['minEdgeRecall']}")
    if "minEdgeF1" in spec and edge_f1 + EPS < float(spec["minEdgeF1"]):
        details.append(f"{label}.edgeF1TooLow:{edge_f1:.2f}<{spec['minEdgeF1']}")
    if "maxForbiddenEdgeRate" in spec and forbidden_rate > float(spec["maxForbiddenEdgeRate"]) + EPS:
        details.append(f"{label}.forbiddenEdgeRateTooHigh:{forbidden_rate:.2f}>{spec['maxForbiddenEdgeRate']}")
    if generated_forbidden:
        details.append(f"{label}.forbiddenEdges:" + ",".join(sorted(generated_forbidden)))


def graph_edge_text(edge: dict[str, Any]) -> str:
    return " ".join(str(edge.get(k) or "") for k in ("fromLabel", "toLabel", "edgeType"))


def check_stored_graph_edges(label: str, edges: list[dict[str, Any]], spec: dict[str, Any], details: list[str], metrics: list[str]) -> None:
    expected_edges = [edge for edge in spec.get("expectedEdges", []) if isinstance(edge, dict)]
    forbidden_edges = [edge for edge in spec.get("forbiddenEdges", []) if isinstance(edge, dict)]
    optional_edges = [edge for edge in spec.get("optionalEdges", []) if isinstance(edge, dict)]
    edge_texts = [graph_edge_text(edge) for edge in edges]

    def present(rule: dict[str, Any]) -> bool:
        return any(edge_present(text, rule) for text in edge_texts)

    correct_expected = sum(1 for edge in expected_edges if present(edge))
    correct_optional = sum(1 for edge in optional_edges if present(edge))
    forbidden = sum(1 for edge in forbidden_edges if present(edge))
    generated_total = len(edges)
    expected_total = len({edge_key(edge) for edge in expected_edges})

    edge_precision = ((correct_expected + correct_optional) / generated_total) if generated_total else 0.0
    edge_recall = (correct_expected / expected_total) if expected_total else 1.0
    edge_f1 = 0.0 if edge_precision + edge_recall == 0 else 2 * edge_precision * edge_recall / (edge_precision + edge_recall)
    forbidden_rate = (forbidden / generated_total) if generated_total else 0.0

    metrics.append(
        f"{label}:storedEdgePrecision={edge_precision:.2f},storedEdgeRecall={edge_recall:.2f},storedEdgeF1={edge_f1:.2f},storedForbiddenEdgeRate={forbidden_rate:.2f},correctEdges={correct_expected},optionalEdges={correct_optional},forbiddenEdges={forbidden},storedEdges={generated_total}"
    )
    if "minEdgePrecision" in spec and edge_precision + EPS < float(spec["minEdgePrecision"]):
        details.append(f"{label}.storedEdgePrecisionTooLow:{edge_precision:.2f}<{spec['minEdgePrecision']}")
    if "minEdgeRecall" in spec and edge_recall + EPS < float(spec["minEdgeRecall"]):
        details.append(f"{label}.storedEdgeRecallTooLow:{edge_recall:.2f}<{spec['minEdgeRecall']}")
    if "minEdgeF1" in spec and edge_f1 + EPS < float(spec["minEdgeF1"]):
        details.append(f"{label}.storedEdgeF1TooLow:{edge_f1:.2f}<{spec['minEdgeF1']}")
    if "maxForbiddenEdgeRate" in spec and forbidden_rate > float(spec["maxForbiddenEdgeRate"]) + EPS:
        details.append(f"{label}.storedForbiddenEdgeRateTooHigh:{forbidden_rate:.2f}>{spec['maxForbiddenEdgeRate']}")


def check_timeline_snapshot(
    label: str,
    hits: list[dict[str, Any]],
    text: str,
    spec: dict[str, Any],
    previous_count: int | None,
    details: list[str],
    metrics: list[str],
) -> int:
    count = len(hits)
    dup = duplicate_rate(hits)
    counts = topic_counts(hits)
    entropy = normalized_entropy(counts)
    expected_topics = [str(x) for x in spec.get("expectedTopics", [])]
    drift = max(0.0, entropy - expected_entropy(expected_topics))
    metrics.append(
        f"{label}:count={count},dup={dup:.2f},entropy={entropy:.2f},drift={drift:.2f},topics={counts}"
    )

    if "minMemoryCount" in spec and count < int(spec["minMemoryCount"]):
        details.append(f"{label}.memoryTooShort:{count}<{spec['minMemoryCount']}")
    if "maxMemoryCount" in spec and count > int(spec["maxMemoryCount"]):
        details.append(f"{label}.memoryTooLong:{count}>{spec['maxMemoryCount']}")
    if previous_count is not None and "maxLengthGrowth" in spec and count - previous_count > int(spec["maxLengthGrowth"]):
        details.append(f"{label}.lengthGrowthTooHigh:{previous_count}->{count}")
    if "maxDuplicateRate" in spec and dup > float(spec["maxDuplicateRate"]):
        details.append(f"{label}.duplicateRateTooHigh:{dup:.2f}>{spec['maxDuplicateRate']}")
    if "maxEntropyDrift" in spec and drift > float(spec["maxEntropyDrift"]):
        details.append(f"{label}.entropyDriftTooHigh:{drift:.2f}>{spec['maxEntropyDrift']}")

    missing_topics = sorted(set(expected_topics) - set(counts))
    if missing_topics:
        details.append(f"{label}.missingTopics:" + ",".join(missing_topics))
    forbidden_topics = sorted(set(str(x) for x in spec.get("forbidTopics", [])) & set(counts))
    if forbidden_topics:
        details.append(f"{label}.forbiddenTopics:" + ",".join(forbidden_topics))
    check_l2_query("", label, hits, text, spec, details)
    return count


def check_recall_quality_probe(label: str, hits: list[dict[str, Any]], text: str, spec: dict[str, Any], details: list[str], metrics: list[str]) -> None:
    keywords = [str(x) for x in spec.get("relevantKeywords", spec.get("expectedKeywords", []))]
    coverage = keyword_coverage(text, keywords)
    rank = first_relevant_rank(hits, keywords)
    mrr = 0.0 if rank is None else 1.0 / rank
    metrics.append(f"{label}:coverage={coverage:.2f},mrr={mrr:.2f},rank={rank or 'NA'}")
    if "minKeywordCoverage" in spec and coverage < float(spec["minKeywordCoverage"]):
        details.append(f"{label}.keywordCoverageTooLow:{coverage:.2f}<{spec['minKeywordCoverage']}")
    if "minMrr" in spec and mrr < float(spec["minMrr"]):
        details.append(f"{label}.mrrTooLow:{mrr:.2f}<{spec['minMrr']}")
    check_l2_query("", label, hits, text, spec, details)


def evaluate_case(base_url: str, case: dict[str, Any], run_id: str, timeout_sec: float, bearer_token: str) -> MemoryCaseResult:
    case_id = str(case.get("caseId", "UNKNOWN"))
    layer = str(case.get("layer", "unknown"))
    details: list[str] = []
    metrics: list[str] = []
    total_latency = 0.0
    preview = ""
    try:
        for index, spec in enumerate(case.get("memoryGraphExtracts") or [], start=1):
            edges, text, latency = extract_memory_graph(base_url, case_id, run_id, spec, timeout_sec, bearer_token)
            total_latency += latency
            preview = text[:220]
            if spec.get("graphExpect"):
                check_stored_graph_edges(f"memoryGraphExtract{index}", edges, spec["graphExpect"], details, metrics)

        for item in case.get("l2Writes") or []:
            write_l2(base_url, case_id, run_id, item, timeout_sec, bearer_token)

        snapshots_by_turn: dict[int, list[dict[str, Any]]] = {}
        for spec in case.get("timelineSnapshots") or []:
            snapshots_by_turn.setdefault(int(spec.get("afterTurn", 0)), []).append(spec)
        probes_by_turn: dict[int, list[dict[str, Any]]] = {}
        for spec in case.get("recallQualityProbes") or []:
            probes_by_turn.setdefault(int(spec.get("afterTurn", 0)), []).append(spec)
        snapshot_counts: list[int] = []

        for snapshot_index, spec in enumerate(snapshots_by_turn.get(0, []), start=1):
            hits, text, latency = query_l2(base_url, case_id, run_id, spec, timeout_sec, bearer_token)
            total_latency += latency
            preview = text[:220]
            count = check_timeline_snapshot(
                f"preTurn.snapshot{snapshot_index}",
                hits,
                text,
                spec,
                None,
                details,
                metrics,
            )
            snapshot_counts.append(count)

        for probe_index, spec in enumerate(probes_by_turn.get(0, []), start=1):
            hits, text, latency = query_l2(base_url, case_id, run_id, spec, timeout_sec, bearer_token)
            total_latency += latency
            preview = text[:220]
            check_recall_quality_probe(f"preTurn.recallProbe{probe_index}", hits, text, spec, details, metrics)

        if case.get("l2Query"):
            hits, text, latency = query_l2(base_url, case_id, run_id, case["l2Query"], timeout_sec, bearer_token)
            total_latency += latency
            preview = text[:220]
            check_l2_query(case_id, "l2Query", hits, text, case["l2Query"], details)
            check_recall_quality_probe("l2QueryQuality", hits, text, case["l2Query"], details, metrics)

        for index, turn in enumerate(case.get("turns") or [], start=1):
            answer, latency, data = chat_turn(base_url, case_id, run_id, turn, timeout_sec, bearer_token)
            total_latency += latency
            preview = answer[:220]
            missing = contains_all(answer, [str(x) for x in turn.get("expectedKeywords", [])])
            forbidden = contains_any(answer, [str(x) for x in turn.get("forbidKeywords", [])])
            if missing:
                details.append(f"turn{index}.missingKeywords:" + ",".join(missing))
            if forbidden:
                details.append(f"turn{index}.forbiddenKeywords:" + ",".join(forbidden))
            if turn.get("contextExpect"):
                check_context_assembly(f"turn{index}.context", data, turn["contextExpect"], details, metrics)
            if turn.get("graphExpect"):
                check_graph_edges(f"turn{index}.graph", answer, data, turn["graphExpect"], details, metrics)

            for snapshot_index, spec in enumerate(snapshots_by_turn.get(index, []), start=1):
                hits, text, latency = query_l2(base_url, case_id, run_id, spec, timeout_sec, bearer_token)
                total_latency += latency
                preview = text[:220]
                previous = snapshot_counts[-1] if snapshot_counts else None
                count = check_timeline_snapshot(
                    f"turn{index}.snapshot{snapshot_index}",
                    hits,
                    text,
                    spec,
                    previous,
                    details,
                    metrics,
                )
                snapshot_counts.append(count)

            for probe_index, spec in enumerate(probes_by_turn.get(index, []), start=1):
                hits, text, latency = query_l2(base_url, case_id, run_id, spec, timeout_sec, bearer_token)
                total_latency += latency
                preview = text[:220]
                check_recall_quality_probe(f"turn{index}.recallProbe{probe_index}", hits, text, spec, details, metrics)

        for index, spec in enumerate(case.get("postL2Queries") or [], start=1):
            hits, text, latency = query_l2(base_url, case_id, run_id, spec, timeout_sec, bearer_token)
            total_latency += latency
            preview = text[:220]
            check_l2_query(case_id, f"postL2Query{index}", hits, text, spec, details)
            check_recall_quality_probe(f"postL2Query{index}Quality", hits, text, spec, details, metrics)

        return MemoryCaseResult(case_id, layer, not details, total_latency, details, metrics, preview)
    except Exception as exc:
        return MemoryCaseResult(case_id, layer, False, total_latency, [f"ERROR:{type(exc).__name__}:{exc}"], metrics, preview)


def markdown_report(results: list[MemoryCaseResult]) -> str:
    total = len(results)
    ok_count = sum(1 for r in results if r.ok)
    lines = [
        "# Memory Priority Evaluation Report",
        "",
        f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"- Cases: {total}",
        f"- Success rate: {ok_count / total:.2%}" if total else "- Success rate: 0.00%",
        "",
        "## Layer Summary",
        "",
        "| Layer | Cases | Success Rate |",
        "|---|---:|---:|",
    ]
    layers = sorted({r.layer for r in results})
    for layer in layers:
        layer_results = [r for r in results if r.layer == layer]
        layer_ok = sum(1 for r in layer_results if r.ok)
        lines.append(f"| {layer} | {len(layer_results)} | {layer_ok / len(layer_results):.2%} |")
    lines.extend([
        "",
        "## Cases",
        "",
        "| Case | Layer | OK | Latency ms | Details | Metrics | Preview |",
        "|---|---|---:|---:|---|---|---|",
    ])
    for r in results:
        preview = r.answer_preview.replace("|", "\\|").replace("\n", " ")[:180]
        metric_text = ";".join(r.metrics).replace("|", "\\|").replace("\n", " ")[:220]
        lines.append(f"| {r.case_id} | {r.layer} | {str(r.ok).lower()} | {r.latency_ms:.1f} | `{';'.join(r.details)}` | {metric_text} | {preview} |")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate memory priority and conflict guards.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/memory_priority_eval_dataset.json")
    parser.add_argument("--timeout-sec", type=float, default=120)
    parser.add_argument("--out", default="")
    parser.add_argument("--run-id", default=datetime.now().strftime("%Y%m%d%H%M%S"), help="Isolate eval user/session ids. Defaults to current timestamp.")
    add_auth_args(parser)
    args = parser.parse_args()
    args.bearer_token = resolve_bearer_token(args, args.base_url, args.timeout_sec)

    cases = load_json(Path(args.dataset))
    if not isinstance(cases, list):
        print("Dataset must be a JSON array.", file=sys.stderr)
        return 2

    results = [evaluate_case(args.base_url, case, args.run_id, args.timeout_sec, args.bearer_token) for case in cases]
    for result in results:
        print(f"[{'OK' if result.ok else 'RISK'}] {result.case_id} latency={result.latency_ms:.1f} details={result.details}")

    report = markdown_report(results)
    out_path = Path(args.out) if args.out else Path(f"memory_priority_eval_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(report, encoding="utf-8")
    print(f"Report written to {out_path}")
    return 0 if all(r.ok for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
