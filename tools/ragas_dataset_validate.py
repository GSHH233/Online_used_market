#!/usr/bin/env python3
"""Validate enterprise RAGAS dataset structure and holdout isolation."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any


DEFAULT_DATASETS = {
    "smoke": "eval/datasets/ragas_rag_smoke_dataset.json",
    "regression": "eval/datasets/ragas_rag_regression_dataset.json",
    "holdout": "eval/datasets/ragas_rag_holdout_dataset.json",
    "development_v2": "eval/datasets/ragas_rag_development_v2_dataset.json",
    "holdout_v2": "eval/datasets/ragas_rag_holdout_v2_dataset.json",
    "development_v3": "eval/datasets/ragas_rag_development_v3_dataset.json",
    "holdout_v3": "eval/datasets/ragas_rag_holdout_v3_dataset.json",
    "development_v4": "eval/datasets/ragas_rag_development_v4_dataset.json",
    "holdout_v4": "eval/datasets/ragas_rag_holdout_v4_dataset.json",
    "development_v5": "eval/datasets/ragas_rag_development_v5_dataset.json",
}
EXPECTED_COUNTS = {
    "smoke": 10,
    "regression": 50,
    "holdout": 15,
    "development_v2": 24,
    "holdout_v2": 20,
    "development_v3": 30,
    "holdout_v3": 25,
    "development_v4": 30,
    "holdout_v4": 30,
    "development_v5": 32,
}


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def normalized_question(value: str) -> str:
    return re.sub(r"[^\w\u4e00-\u9fff]+", "", value.lower())


def bigrams(value: str) -> set[str]:
    normalized = normalized_question(value)
    if len(normalized) < 2:
        return {normalized} if normalized else set()
    return {normalized[index:index + 2] for index in range(len(normalized) - 1)}


def jaccard(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)


def validate(catalog_path: Path, dataset_paths: dict[str, Path], leakage_threshold: float) -> tuple[list[str], dict[str, Any]]:
    catalog_raw = load_json(catalog_path)
    contexts = catalog_raw.get("contexts", {}) if isinstance(catalog_raw, dict) else {}
    issues: list[str] = []
    all_cases: list[dict[str, Any]] = []
    cases_by_split: dict[str, list[dict[str, Any]]] = {}
    ids: set[str] = set()
    normalized_questions: dict[str, str] = {}

    for split, path in dataset_paths.items():
        raw = load_json(path)
        cases = raw.get("cases", []) if isinstance(raw, dict) else raw
        if not isinstance(cases, list):
            issues.append(f"{split}:datasetNotArray")
            continue
        cases_by_split[split] = cases
        if len(cases) != EXPECTED_COUNTS.get(split):
            issues.append(f"{split}:count={len(cases)}!=expected={EXPECTED_COUNTS.get(split)}")
        for index, case in enumerate(cases):
            if not isinstance(case, dict):
                issues.append(f"{split}:{index}:notObject")
                continue
            case_id = str(case.get("caseId", "")).strip()
            question = str(case.get("question", "")).strip()
            if not case_id or case_id in ids:
                issues.append(f"{split}:{index}:missingOrDuplicateCaseId={case_id}")
            ids.add(case_id)
            if str(case.get("split", "")) != split:
                issues.append(f"{case_id}:split={case.get('split')}!=expected={split}")
            for field in ("category", "queryType", "difficulty"):
                if not str(case.get(field, "")).strip():
                    issues.append(f"{case_id}:missing={field}")
            if not question:
                issues.append(f"{case_id}:missingQuestion")
            normalized = normalized_question(question)
            if normalized in normalized_questions:
                issues.append(f"duplicateQuestion={case_id}/{normalized_questions[normalized]}")
            normalized_questions[normalized] = case_id
            reference_ids = [str(value) for value in case.get("referenceContextIds", [])]
            if not reference_ids:
                issues.append(f"{case_id}:missingReferenceContextIds")
            for context_id in reference_ids:
                if context_id not in contexts:
                    issues.append(f"{case_id}:unknownContext={context_id}")
            if split in {"development_v4", "holdout_v4", "development_v5"}:
                if not str(case.get("reference", "")).strip():
                    issues.append(f"{case_id}:missingReference")
                reference_facts = case.get("referenceFacts")
                if not isinstance(reference_facts, list) or not any(str(value).strip() for value in reference_facts):
                    issues.append(f"{case_id}:missingReferenceFacts")
            all_cases.append(case)

    tuning_cases = [
        *cases_by_split.get("smoke", []),
        *cases_by_split.get("regression", []),
        *cases_by_split.get("development_v2", []),
        *cases_by_split.get("development_v3", []),
        *cases_by_split.get("development_v4", []),
        *cases_by_split.get("development_v5", []),
    ]
    leakage_pairs: list[tuple[str, str, float]] = []
    prior_holdouts: list[dict[str, Any]] = []
    for holdout_split in ("holdout", "holdout_v2", "holdout_v3", "holdout_v4"):
        current_holdouts = cases_by_split.get(holdout_split, [])
        for holdout in current_holdouts:
            holdout_terms = bigrams(str(holdout.get("question", "")))
            for tuning in [*tuning_cases, *prior_holdouts]:
                score = jaccard(holdout_terms, bigrams(str(tuning.get("question", ""))))
                if score >= leakage_threshold:
                    leakage_pairs.append((str(holdout.get("caseId")), str(tuning.get("caseId")), score))
        prior_holdouts.extend(current_holdouts)
    if leakage_pairs:
        issues.extend(f"holdoutLeakage={left}/{right}:{score:.3f}" for left, right, score in leakage_pairs)

    summary = {
        "total": len(all_cases),
        "splitCounts": {split: len(cases) for split, cases in cases_by_split.items()},
        "catalogContexts": len(contexts),
        "categories": dict(Counter(str(case.get("category")) for case in all_cases)),
        "queryTypes": dict(Counter(str(case.get("queryType")) for case in all_cases)),
        "difficulties": dict(Counter(str(case.get("difficulty")) for case in all_cases)),
        "holdoutLeakagePairs": leakage_pairs,
    }
    return issues, summary


def write_report(path: Path, issues: list[str], summary: dict[str, Any], threshold: float) -> None:
    lines = [
        "# RAGAS Dataset Validation Report",
        "",
        f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"- Verdict: {'PASS' if not issues else 'RISK'}",
        f"- Total cases: {summary['total']}",
        f"- Split counts: {summary['splitCounts']}",
        f"- Catalog contexts: {summary['catalogContexts']}",
        f"- Holdout leakage threshold: {threshold:.2f}",
        f"- Issues: {issues or 'none'}",
        "",
        "## Distribution",
        "",
        f"- Categories: {summary['categories']}",
        f"- Query types: {summary['queryTypes']}",
        f"- Difficulties: {summary['difficulties']}",
        "",
        "> Holdout leakage uses character-bigram Jaccard similarity. It is a guardrail against near-duplicate wording, not a semantic-equivalence judge.",
    ]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate enterprise RAGAS datasets")
    parser.add_argument("--catalog", default="eval/datasets/ragas_knowledge_catalog.json")
    parser.add_argument("--smoke", default=DEFAULT_DATASETS["smoke"])
    parser.add_argument("--regression", default=DEFAULT_DATASETS["regression"])
    parser.add_argument("--holdout", default=DEFAULT_DATASETS["holdout"])
    parser.add_argument("--development-v2", default=DEFAULT_DATASETS["development_v2"])
    parser.add_argument("--holdout-v2", default=DEFAULT_DATASETS["holdout_v2"])
    parser.add_argument("--development-v3", default=DEFAULT_DATASETS["development_v3"])
    parser.add_argument("--holdout-v3", default=DEFAULT_DATASETS["holdout_v3"])
    parser.add_argument("--development-v4", default=DEFAULT_DATASETS["development_v4"])
    parser.add_argument("--holdout-v4", default=DEFAULT_DATASETS["holdout_v4"])
    parser.add_argument("--development-v5", default=DEFAULT_DATASETS["development_v5"])
    parser.add_argument("--leakage-threshold", type=float, default=0.72)
    parser.add_argument("--out", default="eval/reports/ragas_dataset_validation_report.md")
    args = parser.parse_args()
    paths = {
        "smoke": Path(args.smoke),
        "regression": Path(args.regression),
        "holdout": Path(args.holdout),
        "development_v2": Path(args.development_v2),
        "holdout_v2": Path(args.holdout_v2),
        "development_v3": Path(args.development_v3),
        "holdout_v3": Path(args.holdout_v3),
        "development_v4": Path(args.development_v4),
        "holdout_v4": Path(args.holdout_v4),
        "development_v5": Path(args.development_v5),
    }
    paths = {split: path for split, path in paths.items() if path.exists()}
    issues, summary = validate(Path(args.catalog), paths, args.leakage_threshold)
    write_report(Path(args.out), issues, summary, args.leakage_threshold)
    print(f"verdict={'PASS' if not issues else 'RISK'} summary={json.dumps(summary, ensure_ascii=False)}")
    print(f"issues={issues}")
    print(f"written to {args.out}")
    return 0 if not issues else 1


if __name__ == "__main__":
    raise SystemExit(main())
