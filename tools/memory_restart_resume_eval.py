#!/usr/bin/env python3
"""Resume one synthetic memory conversation after an application restart.

Uses the local SMS-code Redis key only for an account named mem_eval_*.
No JWT, password, phone, code, or memory content is written to the report.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import pymysql
import redis

from memory_scope_live_eval import chat, post_json


def mysql_connection():
    password = os.environ.get("MYSQL_PASSWORD")
    if not password:
        raise RuntimeError("MYSQL_PASSWORD is required")
    return pymysql.connect(
        host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
        port=int(os.environ.get("MYSQL_PORT", "3306")),
        user=os.environ.get("MYSQL_USERNAME", "root"),
        password=password,
        database=os.environ.get("MYSQL_DATABASE", "used_market"),
        connect_timeout=5,
        read_timeout=5,
    )


def current_state(user_id: int, conversation_id: str) -> tuple[str, int, str]:
    connection = mysql_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT username, phone FROM ums_user WHERE id=%s", (user_id,))
            user = cursor.fetchone()
            if not user or not str(user[0]).startswith("mem_eval_") or not user[1]:
                raise RuntimeError("Only synthetic mem_eval_* users are allowed")
            cursor.execute(
                "SELECT id, metadata_json FROM ai_l2_memory_record "
                "WHERE user_id=%s AND session_id=%s AND memory_type='SESSION_SUMMARY' "
                "ORDER BY updated_time DESC, id DESC LIMIT 1",
                (str(user_id), conversation_id),
            )
            row = cursor.fetchone()
            if not row:
                raise RuntimeError("No persisted summary for this synthetic user and conversation")
            metadata = json.loads(row[1] or "{}")
            model = str((metadata.get("kv") or {}).get("model") or "")
            if not model:
                raise RuntimeError("The persisted summary has no model to verify after restart")
            return str(user[1]), int(row[0]), model
    finally:
        connection.close()


def sms_login(base_url: str, phone: str, expected_user_id: int) -> str:
    client = redis.Redis(
        host=os.environ.get("REDIS_HOST", "127.0.0.1"),
        port=int(os.environ.get("REDIS_PORT", "6379")),
        password=os.environ.get("REDIS_PASSWORD") or None,
        db=int(os.environ.get("REDIS_DATABASE", "5")),
        socket_connect_timeout=3,
        socket_timeout=3,
    )
    client.ping()
    post_json(base_url, "/api/auth/sms/send", {"phone": phone})
    code = client.get("login:sms:code:" + phone)
    if not code:
        raise RuntimeError("Local SMS test code was not found in Redis")
    login = post_json(base_url, "/api/auth/sms/login", {"phone": phone, "code": code.decode()})
    if int(login["userId"]) != expected_user_id:
        raise RuntimeError("SMS login returned a different user")
    return str(login["token"])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--user-id", type=int, required=True)
    parser.add_argument("--conversation-id", required=True)
    parser.add_argument("--timeout-sec", type=float, default=90.0)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()
    report: dict = {
        "userId": args.user_id,
        "conversationId": args.conversation_id,
        "status": "ERROR",
    }
    try:
        phone, previous_id, expected_model = current_state(args.user_id, args.conversation_id)
        report["previousSummaryId"] = previous_id
        token = sms_login(args.base_url, phone, args.user_id)
        parsed = urlsplit(args.base_url)
        ws_url = urlunsplit(
            ("wss" if parsed.scheme == "https" else "ws", parsed.netloc, "/ws/ai/rag-chat", "", "")
        )
        result = asyncio.run(
            chat(ws_url, token, args.conversation_id, "沿用前面的信息，给一个简短的发布标题。", args.timeout_sec)
        )
        _, latest_id, latest_model = current_state(args.user_id, args.conversation_id)
        report["chat"] = result
        report["latestSummaryId"] = latest_id
        report["modelPreserved"] = latest_model == expected_model
        report["status"] = "PASS" if latest_id > previous_id and latest_model == expected_model else "RISK"
    except Exception as error:
        report["error"] = str(error)
        print(f"[ERROR] {error}")
    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"memory_restart_resume_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"status={report['status']} report={output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
