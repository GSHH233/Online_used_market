#!/usr/bin/env python3
"""Run deterministic Supervisor quality and serial/parallel comparison evals."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--development-dataset",
        default="eval/datasets/aiops_diagnosis_quality_development_dataset.json",
    )
    parser.add_argument(
        "--holdout-dataset",
        default="eval/datasets/aiops_diagnosis_quality_holdout_dataset.json",
    )
    parser.add_argument("--output-dir", default="eval/reports")
    parser.add_argument("--rounds", type=int, default=3)
    args = parser.parse_args()

    datasets = [Path(args.development_dataset), Path(args.holdout_dataset)]
    missing = [str(path) for path in datasets if not path.exists()]
    if missing:
        print("missing datasets: " + ", ".join(missing), file=sys.stderr)
        return 2

    maven = shutil.which("mvn.cmd") or shutil.which("mvn")
    if not maven:
        print("maven executable not found in PATH", file=sys.stderr)
        return 2
    command = [
        maven,
        "-q",
        "-Dtest=AiopsDiagnosisQualityEvalTest",
        f"-Daiops.quality.datasets={';'.join(str(path) for path in datasets)}",
        f"-Daiops.quality.outputDir={args.output_dir}",
        f"-Daiops.quality.rounds={max(3, args.rounds)}",
        "test",
    ]
    completed = subprocess.run(command, check=False)
    if completed.returncode != 0:
        return completed.returncode

    failed = False
    for dataset in datasets:
        name = json.loads(dataset.read_text(encoding="utf-8"))["name"]
        report_path = Path(args.output_dir) / f"aiops_diagnosis_quality_{name}_report.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        status = "OK" if report.get("passed") else "RISK"
        print(
            f"[{status}] {name} cases={report['caseCount']} "
            f"root={report['parallelRootCauseAccuracy']:.3f} "
            f"evidence={report['evidenceSupportRate']:.3f} "
            f"deferral={report['deferralAccuracy']:.3f} "
            f"parallelP95={report['parallelP95Ms']:.1f}ms "
            f"serialP95={report['serialP95Ms']:.1f}ms"
        )
        print(f"written to {report_path}")
        failed = failed or not report.get("passed", False)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
