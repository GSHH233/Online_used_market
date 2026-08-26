#!/usr/bin/env python3
"""Read-only checks for knowledge lifecycle and revision invariants."""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any


def strip_bearer(value: str) -> str:
    token = value.strip()
    return token[7:].strip() if token.lower().startswith("bearer ") else token


def fetch_docs(base_url: str, token: str, limit: int, timeout_sec: float) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode({"limit": limit})
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/ai/knowledge/docs?" + query,
        headers={"Authorization": "Bearer " + strip_bearer(token)},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {detail[:300]}") from exc
    if payload.get("code") != 200:
        raise RuntimeError(f"appCode={payload.get('code')}, message={payload.get('message')}")
    data = payload.get("data")
    return [item for item in data if isinstance(item, dict)] if isinstance(data, list) else []


def evaluate(docs: list[dict[str, Any]], strict_no_legacy: bool) -> tuple[str, list[str], dict[str, Any]]:
    statuses = Counter(str(doc.get("status") or "UNKNOWN") for doc in docs)
    managed = [doc for doc in docs if doc.get("lifecycleManaged") is True]
    legacy = [doc for doc in docs if doc.get("lifecycleManaged") is not True]
    active_missing_index = [doc.get("id") for doc in managed
                            if doc.get("status") == "ACTIVE" and not doc.get("activeIndexVersion")]
    retired_missing_retention = [doc.get("id") for doc in managed
                                 if doc.get("status") == "RETIRED" and not doc.get("retentionUntil")]
    retired_invalid_cleanup = [doc.get("id") for doc in managed
                               if doc.get("status") == "RETIRED"
                               and str(doc.get("cleanupStatus") or "PENDING")
                               not in {"PENDING", "RUNNING", "FAILED", "COMPLETED", "ROLLBACK_PENDING"}]

    active_by_logical: dict[str, list[int]] = defaultdict(list)
    content_by_hash: dict[str, list[int]] = defaultdict(list)
    for doc in managed:
        status = str(doc.get("status") or "")
        logical_id = str(doc.get("logicalKnowledgeId") or "").strip()
        content_hash = str(doc.get("contentHash") or "").strip()
        if status == "ACTIVE" and logical_id:
            active_by_logical[logical_id].append(int(doc.get("id")))
        if status in {"DRAFT", "ACTIVE"} and content_hash:
            content_by_hash[content_hash].append(int(doc.get("id")))

    multiple_active = {key: ids for key, ids in active_by_logical.items() if len(ids) > 1}
    duplicate_content = {key: ids for key, ids in content_by_hash.items() if len(ids) > 1}
    issues: list[str] = []
    if active_missing_index:
        issues.append(f"activeMissingIndex={active_missing_index}")
    if retired_missing_retention:
        issues.append(f"retiredMissingRetention={retired_missing_retention}")
    if retired_invalid_cleanup:
        issues.append(f"retiredInvalidCleanup={retired_invalid_cleanup}")
    if multiple_active:
        issues.append(f"multipleActiveRevisions={multiple_active}")
    if duplicate_content:
        issues.append(f"duplicateManagedContent={duplicate_content}")
    if strict_no_legacy and legacy:
        issues.append(f"legacyDocuments={len(legacy)}")

    if issues:
        verdict = "RISK"
    elif legacy:
        verdict = "PASS_WITH_MIGRATION_PENDING"
    else:
        verdict = "PASS"
    summary = {
        "total": len(docs),
        "managed": len(managed),
        "legacy": len(legacy),
        "managedDraft": sum(doc.get("status") == "DRAFT" for doc in managed),
        "managedActive": sum(doc.get("status") == "ACTIVE" for doc in managed),
        "managedRetired": sum(doc.get("status") == "RETIRED" for doc in managed),
        "managedDisabled": sum(doc.get("status") == "DISABLED" for doc in managed),
        "statuses": dict(statuses),
        "activeMissingIndex": active_missing_index,
        "retiredMissingRetention": retired_missing_retention,
        "retiredInvalidCleanup": retired_invalid_cleanup,
        "multipleActiveRevisions": multiple_active,
        "duplicateManagedContent": duplicate_content,
    }
    return verdict, issues, summary


def write_report(output: Path, verdict: str, issues: list[str], summary: dict[str, Any], strict: bool) -> None:
    lines = [
        "# Knowledge Lifecycle Evaluation Report",
        "",
        f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"- Verdict: {verdict}",
        f"- Strict no legacy: {str(strict).lower()}",
        f"- Total documents: {summary['total']}",
        f"- Managed documents: {summary['managed']}",
        f"- Legacy documents: {summary['legacy']}",
        f"- Managed DRAFT / ACTIVE / RETIRED / DISABLED: {summary['managedDraft']} / {summary['managedActive']} / {summary['managedRetired']} / {summary['managedDisabled']}",
        f"- Status counts: {summary['statuses']}",
        f"- Issues: {issues or 'none'}",
        "",
        "## Invariants",
        "",
        f"- ACTIVE without activeIndexVersion: {summary['activeMissingIndex'] or 'none'}",
        f"- RETIRED without retentionUntil: {summary['retiredMissingRetention'] or 'none'}",
        f"- RETIRED with invalid cleanup status: {summary['retiredInvalidCleanup'] or 'none'}",
        f"- Multiple ACTIVE revisions per logicalKnowledgeId: {summary['multipleActiveRevisions'] or 'none'}",
        f"- Duplicate managed contentHash: {summary['duplicateManagedContent'] or 'none'}",
        "",
        "> Legacy documents remain readable only while KNOWLEDGE_ALLOW_LEGACY_DRAFTS=true. Reindex them to enter the managed ACTIVE lifecycle, then run this script with --strict-no-legacy.",
    ]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate knowledge lifecycle invariants")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--internal-bearer-token", default=os.getenv("INTERNAL_BEARER_TOKEN", ""))
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--timeout-sec", type=float, default=15.0)
    parser.add_argument("--strict-no-legacy", action="store_true")
    parser.add_argument("--out", default="eval/reports/knowledge_lifecycle_eval_report.md")
    args = parser.parse_args()
    if not strip_bearer(args.internal_bearer_token):
        raise SystemExit("missing --internal-bearer-token or INTERNAL_BEARER_TOKEN")
    try:
        docs = fetch_docs(args.base_url, args.internal_bearer_token, max(1, min(args.limit, 100)), args.timeout_sec)
        verdict, issues, summary = evaluate(docs, args.strict_no_legacy)
    except Exception as exc:
        verdict, issues, summary = "RISK", [str(exc)], {
            "total": 0, "managed": 0, "legacy": 0,
            "managedDraft": 0, "managedActive": 0, "managedRetired": 0, "managedDisabled": 0,
            "statuses": {}, "activeMissingIndex": [],
            "retiredMissingRetention": [], "retiredInvalidCleanup": [],
            "multipleActiveRevisions": {}, "duplicateManagedContent": {},
        }
    output = Path(args.out)
    write_report(output, verdict, issues, summary, args.strict_no_legacy)
    print(f"verdict={verdict} summary={json.dumps(summary, ensure_ascii=False)} issues={issues}")
    print(f"written to {output}")
    return 1 if verdict == "RISK" else 0


if __name__ == "__main__":
    raise SystemExit(main())
