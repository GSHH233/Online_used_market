#!/usr/bin/env python3
"""Generate deterministic knowledge-ingestion files for Postman and automated evaluation."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from ingestion_chunk_eval import build_fixture


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="eval/datasets/ingestion_chunk_eval_dataset.json")
    parser.add_argument("--output-dir", default="eval/fixtures/knowledge-docs")
    args = parser.parse_args()

    dataset_path = Path(args.dataset)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    cases: list[dict[str, Any]] = json.loads(dataset_path.read_text(encoding="utf-8"))
    cache: dict[str, tuple[str, bytes]] = {}
    manifest: list[dict[str, Any]] = []

    for case in cases:
        source_name, content = build_fixture(case, cache)
        extension = Path(source_name).suffix
        target_name = f"{case['caseId'].lower()}{extension}"
        target = output_dir / target_name
        target.write_bytes(content)
        cache[case["caseId"]] = (target_name, content)
        manifest.append({
            "caseId": case["caseId"],
            "file": target_name,
            "format": case["format"],
            "title": case.get("title") or case["caseId"],
            "sha256": hashlib.sha256(content).hexdigest(),
            "sizeBytes": len(content),
            "expectRejected": bool(case.get("expectRejected")),
            "duplicateOf": case.get("duplicateOf"),
        })

    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"generated {len(manifest)} fixtures in {output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
