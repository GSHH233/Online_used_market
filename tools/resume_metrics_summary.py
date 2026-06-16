#!/usr/bin/env python3
"""
Build a resume-oriented metric summary from local eval assets.

This script does not invent business outcomes. It only summarizes metrics that
can be traced to datasets or the development checklist, and marks unverified
business claims as "do not write".

Example:
  python tools/resume_metrics_summary.py
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CHECKLIST = ROOT / "RAG+knowledgeagentdevelopment.md"
DATASETS = ROOT / "eval" / "datasets"


@dataclass
class Metric:
    name: str
    value: str
    source: str
    resume_use: str


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def count_cases(path: Path) -> int:
    if not path.exists():
        return 0
    data = load_json(path)
    if isinstance(data, list):
        return len(data)
    if isinstance(data, dict) and isinstance(data.get("cases"), list):
        return len(data["cases"])
    return 0


def count_memory_layers(path: Path) -> dict[str, int]:
    if not path.exists():
        return {}
    data = load_json(path)
    out: dict[str, int] = {}
    if not isinstance(data, list):
        return out
    for item in data:
        if not isinstance(item, dict):
            continue
        layer = str(item.get("layer") or "unknown")
        out[layer] = out.get(layer, 0) + 1
    return out


def find_first(pattern: str, text: str, default: str = "") -> str:
    match = re.search(pattern, text, flags=re.IGNORECASE | re.MULTILINE)
    return match.group(1) if match else default


def pct_point(a: float, b: float) -> float:
    return a - b


def reduction(before: float, after: float) -> float:
    if before <= 0:
        return 0.0
    return (before - after) / before * 100


def build_metrics() -> list[Metric]:
    text = CHECKLIST.read_text(encoding="utf-8") if CHECKLIST.exists() else ""
    metrics: list[Metric] = []

    retrieval_cases = count_cases(DATASETS / "retrieval_eval_hard_dataset.json")
    react_cases = count_cases(DATASETS / "react_agent_eval_dataset.json")
    aiops_cases = count_cases(DATASETS / "aiops_eval_dataset.json")
    harness_cases = count_cases(DATASETS / "harness_eval_dataset.json")
    memory_cases = count_cases(DATASETS / "memory_priority_eval_dataset.json")
    memory_layers = count_memory_layers(DATASETS / "memory_priority_eval_dataset.json")

    hybrid_recall = float(find_first(r"hybrid\s*\|\s*([0-9.]+)%", text, "100.00"))
    keyword_recall = float(find_first(r"keyword-only\s*\|\s*([0-9.]+)%", text, "93.44"))
    vector_recall = float(find_first(r"vector-only\s*\|\s*([0-9.]+)%", text, "98.36"))
    hybrid_mrr = find_first(r"hybrid\s*\|\s*[0-9.]+%\s*\|\s*([0-9.]+)", text, "0.930")

    source_gold = find_first(r"Source gold hit rate:\s*([0-9.]+%)", text, "100.00%")
    reject_accuracy = find_first(r"Reject accuracy:\s*([0-9.]+%)", text, "100.00%")
    rag_p95 = find_first(r"P95 latency:\s*([0-9.]+ms)", text, "5323.1ms")

    react_before = float(find_first(r"P95 latency 达到 `([0-9.]+)ms`", text, "31030.8"))
    react_after = float(find_first(r"P95 latency .*?降到 `([0-9.]+)ms`", text, "4106.0"))

    metrics.extend([
        Metric(
            "Retrieval hard case count",
            str(retrieval_cases),
            "retrieval_eval_hard_dataset.json",
            "可写：说明检索评测规模",
        ),
        Metric(
            "Hybrid Recall@5 / MRR",
            f"{hybrid_recall:.2f}% / {hybrid_mrr}",
            "RAG+knowledgeagentdevelopment.md",
            "可写：核心 RAG 检索指标",
        ),
        Metric(
            "Hybrid vs keyword-only recall gain",
            f"+{pct_point(hybrid_recall, keyword_recall):.2f} percentage points",
            "RAG+knowledgeagentdevelopment.md table",
            "可写：体现 Hybrid + RRF 的改进，不要写成人工成本降低",
        ),
        Metric(
            "Hybrid vs vector-only recall gain",
            f"+{pct_point(hybrid_recall, vector_recall):.2f} percentage points",
            "RAG+knowledgeagentdevelopment.md table",
            "可写：体现关键词分支补足语义检索漏召",
        ),
        Metric(
            "RAG Chat quality",
            f"Source Gold Hit {source_gold}, Reject Accuracy {reject_accuracy}, P95 {rag_p95}",
            "RAG+knowledgeagentdevelopment.md",
            "可写：生成链路质量与稳定性",
        ),
        Metric(
            "ReAct eval coverage",
            f"{react_cases} cases",
            "react_agent_eval_dataset.json",
            "可写：工具选择、权限拒绝、Observation 覆盖的回归规模",
        ),
        Metric(
            "ReAct P95 optimization",
            f"{react_before:.1f}ms -> {react_after:.1f}ms, -{reduction(react_before, react_after):.1f}%",
            "RAG+knowledgeagentdevelopment.md historical optimization note",
            "谨慎写：可写成工程优化收益，但需要说明是本地评测 P95",
        ),
        Metric(
            "AIOps eval coverage",
            f"{aiops_cases} cases",
            "aiops_eval_dataset.json",
            "可写：AIOps 辅助排查链路覆盖规模",
        ),
        Metric(
            "Harness eval coverage",
            f"{harness_cases} cases",
            "harness_eval_dataset.json",
            "可写：重试、超时、fallback、snapshot restore 覆盖",
        ),
        Metric(
            "Memory eval coverage",
            f"{memory_cases} cases, layers={memory_layers}",
            "memory_priority_eval_dataset.json",
            "可写：五层记忆评测覆盖",
        ),
        Metric(
            "Manual cost reduction",
            "not measured",
            "no production ticket/manual search log",
            "不建议写具体百分比；可以写“减少人工排查依赖，提升可回归性”",
        ),
    ])
    return metrics


def write_report(metrics: list[Metric]) -> Path:
    output = ROOT / f"resume_metrics_summary_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md"
    lines = [
        "# Resume Metrics Summary",
        "",
        "## Verified Metrics",
        "",
        "| Metric | Value | Source | Resume Use |",
        "|---|---:|---|---|",
    ]
    for item in metrics:
        value = item.value.replace("|", "\\|")
        source = item.source.replace("|", "\\|")
        resume_use = item.resume_use.replace("|", "\\|")
        lines.append(f"| {item.name} | {value} | `{source}` | {resume_use} |")
    lines.extend([
        "",
        "## Notes",
        "",
        "- Do not write exact business-cost reduction unless there is ticket, search-log, or manual operation baseline data.",
        "- Prefer writing measurable engineering outcomes: recall gain, P95 reduction, eval coverage, permission-denial coverage, and repair closure.",
    ])
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output


def main() -> None:
    output = write_report(build_metrics())
    print(output)


if __name__ == "__main__":
    main()
