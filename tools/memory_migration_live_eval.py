#!/usr/bin/env python3
"""Live HTTP acceptance for ACTIVE versus QUARANTINED L2 memory recall."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

import pymysql


def encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def token(user_id: int, role: str) -> str:
    secret = os.environ.get("JWT_SECRET", "").strip()
    if not secret:
        raise RuntimeError("JWT_SECRET is required")
    now = int(datetime.now().timestamp())
    header = encode(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode())
    payload = encode(json.dumps({
        "userId": user_id,
        "username": "memory-migration-eval",
        "role": role,
        "iat": now,
        "exp": now + 900,
    }, separators=(",", ":")).encode())
    signature = encode(hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest())
    return f"{header}.{payload}.{signature}"


def query(base_url: str, bearer: str, text: str) -> list[dict]:
    params = urllib.parse.urlencode({
        "sessionId": "mem03-live-eval",
        "q": text,
        "topK": 20,
        "threshold": 0.0,
    })
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/ai/l2-memory/debug/query?" + params,
        headers={"Authorization": "Bearer " + bearer},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        body = json.load(response)
    if int(body.get("code") or 0) != 200:
        raise RuntimeError(f"query failed: {body.get('code')} {body.get('message')}")
    return body.get("data") or []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:18080")
    parser.add_argument("--owner-user-id", type=int, default=1)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()
    mysql_password = os.environ.get("MYSQL_PASSWORD")
    if not mysql_password:
        parser.error("MYSQL_PASSWORD is required")

    marker = secrets.token_hex(8)
    active_content = f"mem03active{marker}"
    quarantined_content = f"mem03quarantine{marker}"
    connection = pymysql.connect(
        host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
        port=int(os.environ.get("MYSQL_PORT", "3306")),
        user=os.environ.get("MYSQL_USERNAME", "root"),
        password=mysql_password,
        database=os.environ.get("MYSQL_DATABASE", "used_market"),
        autocommit=True,
    )
    inserted_ids: list[int] = []
    try:
        with connection.cursor() as cursor:
            for content, status in ((active_content, "ACTIVE"), (quarantined_content, "QUARANTINED")):
                cursor.execute(
                    """
                    INSERT INTO ai_l2_memory_record
                      (user_id, session_id, memory_type, content, importance,
                       source_type, assertion_status, scope_type, scope_key,
                       slot_key, revision, status, valid_from)
                    VALUES (%s, 'mem03-live-eval', 'FACT', %s, 0.5,
                            'USER_EXPLICIT', 'REPORTED', 'USER', 'USER',
                            '', 0, %s, CURRENT_TIMESTAMP(6))
                    """,
                    (str(args.owner_user_id), content, status),
                )
                inserted_ids.append(int(cursor.lastrowid))

        bearer = token(args.owner_user_id, "ADMIN")
        active_hits = query(args.base_url, bearer, active_content)
        quarantine_hits = query(args.base_url, bearer, quarantined_content)
        active_ids = {int(hit["id"]) for hit in active_hits if hit.get("id") is not None}
        quarantine_query_ids = {int(hit["id"]) for hit in quarantine_hits if hit.get("id") is not None}
        active_visible = inserted_ids[0] in active_ids
        quarantined_hidden = inserted_ids[1] not in quarantine_query_ids and inserted_ids[1] not in active_ids
        report = {
            "checkedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
            "baseUrl": args.base_url,
            "ownerUserId": args.owner_user_id,
            "activeVisible": active_visible,
            "quarantinedHidden": quarantined_hidden,
            "activeHitCount": len(active_hits),
            "quarantineQueryHitCount": len(quarantine_hits),
            "cleanupPlanned": True,
            "passed": active_visible and quarantined_hidden,
        }
    finally:
        if inserted_ids:
            with connection.cursor() as cursor:
                placeholders = ",".join(["%s"] * len(inserted_ids))
                cursor.execute(
                    f"DELETE FROM ai_l2_memory_record WHERE id IN ({placeholders})",
                    tuple(inserted_ids),
                )
        connection.close()

    report["cleanupCompleted"] = True
    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"memory_migration_live_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"written to {output}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
