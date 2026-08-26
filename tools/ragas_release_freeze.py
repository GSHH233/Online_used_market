#!/usr/bin/env python3
"""Create or verify the immutable implementation manifest for a RAGAS blind run."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_FILES = (
    "src/main/java/org/example/online_used_marketplace/modules/ai/config/TokenGovernanceProperties.java",
    "src/main/java/org/example/online_used_marketplace/modules/ai/service/impl/ChatGenerationServiceImpl.java",
    "src/main/java/org/example/online_used_marketplace/modules/ai/service/impl/RagChatServiceImpl.java",
    "src/main/java/org/example/online_used_marketplace/modules/ai/service/impl/KnowledgeSearchServiceImpl.java",
    "src/main/java/org/example/online_used_marketplace/modules/ai/service/impl/KnowledgeRetrievalExecutor.java",
    "src/main/java/org/example/online_used_marketplace/modules/ai/service/impl/KnowledgeEvidencePlanner.java",
    "src/main/java/org/example/online_used_marketplace/modules/ai/service/impl/KnowledgeQueryConcepts.java",
    "src/main/java/org/example/online_used_marketplace/modules/ai/service/impl/DeterministicKnowledgeReranker.java",
    "src/main/java/org/example/online_used_marketplace/modules/ai/service/impl/KeywordSearchServiceImpl.java",
    "src/main/java/org/example/online_used_marketplace/modules/ai/config/ElasticsearchIndexInitializer.java",
    "src/main/resources/application.yml",
    "tools/ragas_rag_eval.py",
    "tools/ragas_dataset_validate.py",
    "tools/ragas_release_freeze.py",
    "eval/datasets/ragas_knowledge_catalog.json",
    "eval/datasets/ragas_rag_development_v4_dataset.json",
    "eval/datasets/ragas_rag_regression_dataset.json",
    "eval/reports/ragas_rag_development_v4_frozen_candidate_report.md",
    "eval/reports/ragas_rag_regression_v4_freeze_report.md",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_value(*args: str) -> str:
    try:
        return subprocess.check_output(
            ["git", *args], stderr=subprocess.DEVNULL, text=True, encoding="utf-8"
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unavailable"


def create(root: Path, manifest_path: Path) -> int:
    missing = [value for value in DEFAULT_FILES if not (root / value).is_file()]
    if missing:
        raise FileNotFoundError(f"cannot freeze; missing files: {missing}")
    files = {value: sha256(root / value) for value in DEFAULT_FILES}
    payload = {
        "schemaVersion": "1.0",
        "protocol": "ragas-blind-holdout-v4",
        "frozenAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "gitHead": git_value("rev-parse", "HEAD"),
        "workingTreeDirty": bool(git_value("status", "--porcelain")),
        "policy": {
            "developmentSplit": "development_v4",
            "blindSplit": "holdout_v4",
            "holdoutCreatedAfterFreeze": True,
            "maximumBlindRuns": 1,
            "postBlindTuningAllowed": False,
        },
        "evaluation": {
            "judgeAdapter": "metric-json",
            "judgeModel": "qwen-max",
            "generationModel": "qwen-turbo",
            "topK": 5,
            "concurrency": 2,
            "minimumMetrics": {
                "faithfulness": 0.80,
                "answerRelevancy": 0.75,
                "llmContextPrecision": 0.70,
                "llmContextRecall": 0.70,
            },
            "performanceGates": {
                "endToEndP95Ms": 4000,
                "generationP95Ms": 2500,
                "averagePromptTokens": 1000,
                "fallbackRate": 0.0,
            },
        },
        "files": files,
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"frozenFiles={len(files)} manifest={manifest_path}")
    return 0


def verify(root: Path, manifest_path: Path) -> int:
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected = payload.get("files", {})
    issues: list[str] = []
    for value, expected_hash in expected.items():
        path = root / value
        if not path.is_file():
            issues.append(f"missing:{value}")
        elif sha256(path) != expected_hash:
            issues.append(f"changed:{value}")
    print(json.dumps({"verified": not issues, "files": len(expected), "issues": issues}, ensure_ascii=False))
    return 0 if not issues else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("create", "verify"))
    parser.add_argument("--root", default=".")
    parser.add_argument("--manifest", default="eval/datasets/ragas_release_freeze_v4_manifest.json")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    manifest = Path(args.manifest)
    if not manifest.is_absolute():
        manifest = root / manifest
    return create(root, manifest) if args.mode == "create" else verify(root, manifest)


if __name__ == "__main__":
    raise SystemExit(main())
