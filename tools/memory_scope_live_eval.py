#!/usr/bin/env python3
"""Isolated live check for authenticated RAG chat memory ownership.

Creates two synthetic USER accounts. It never deletes users or memory rows.
Credentials and JWTs are kept in memory and omitted from the report.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import secrets
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import websockets


def post_json(base_url: str, path: str, payload: dict) -> dict:
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body = json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{path}: HTTP {error.code}") from error
    if body.get("code") != 200:
        raise RuntimeError(f"{path}: code={body.get('code')} message={body.get('message')}")
    return body.get("data") or {}


def create_user(base_url: str, run_id: str, index: int) -> tuple[int, str, str]:
    username = f"mem_eval_{run_id}_{index}"
    password = secrets.token_urlsafe(12)[:18]
    phone = "139" + str(secrets.randbelow(100_000_000)).zfill(8)
    registered = post_json(
        base_url,
        "/api/auth/register",
        {
            "username": username,
            "password": password,
            "confirmPassword": password,
            "nickname": username,
            "phone": phone,
        },
    )
    logged_in = post_json(
        base_url,
        "/api/auth/login",
        {"account": username, "password": password, "loginType": "USERNAME_PASSWORD"},
    )
    if int(registered["userId"]) != int(logged_in["userId"]):
        raise RuntimeError("Registration and login returned different owners")
    return int(logged_in["userId"]), username, logged_in["token"]


async def chat(ws_url: str, token: str, conversation_id: str, question: str, timeout: float) -> dict:
    request_id = f"mem-eval-{secrets.token_hex(5)}"
    started = time.perf_counter()
    async with websockets.connect(ws_url, open_timeout=10) as socket:
        await socket.send(
            json.dumps(
                {
                    "type": "CHAT_START",
                    "requestId": request_id,
                    "conversationId": conversation_id,
                    "question": question,
                    "topK": 3,
                    "token": token,
                },
                ensure_ascii=False,
            )
        )
        events: list[str] = []
        while True:
            event = json.loads(await asyncio.wait_for(socket.recv(), timeout=timeout))
            event_type = event.get("type")
            events.append(str(event_type))
            if event_type == "CHAT_ERROR":
                payload = event.get("payload") or {}
                raise RuntimeError(f"CHAT_ERROR code={payload.get('code')} message={payload.get('message')}")
            if event_type == "CHAT_DONE":
                payload = event.get("payload") or {}
                return {
                    "events": events,
                    "answerChars": len(str(payload.get("answer") or "")),
                    "elapsedMs": round((time.perf_counter() - started) * 1000, 1),
                }


def check_mysql(user_a: int, user_b: int, conversation_id: str, markers: tuple[str, str]) -> dict:
    import pymysql

    password = os.environ.get("MYSQL_PASSWORD")
    if not password:
        raise RuntimeError("MYSQL_PASSWORD environment variable is required for read-only DB assertions")
    connection = pymysql.connect(
        host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
        port=int(os.environ.get("MYSQL_PORT", "3306")),
        user=os.environ.get("MYSQL_USERNAME", "root"),
        password=password,
        database=os.environ.get("MYSQL_DATABASE", "used_market"),
        connect_timeout=5,
        read_timeout=5,
        autocommit=True,
    )
    try:
        with connection.cursor() as cursor:
            results = {}
            for owner, other_marker in ((user_a, markers[1]), (user_b, markers[0])):
                cursor.execute(
                    "SELECT COUNT(*) FROM ai_l2_memory_record "
                    "WHERE user_id=%s AND session_id=%s AND memory_type='SESSION_SUMMARY'",
                    (str(owner), conversation_id),
                )
                summary_count = int(cursor.fetchone()[0])
                cursor.execute(
                    "SELECT COUNT(*) FROM ai_l2_memory_record "
                    "WHERE user_id=%s AND session_id=%s AND content LIKE %s",
                    (str(owner), conversation_id, f"%{other_marker}%"),
                )
                foreign_marker_count = int(cursor.fetchone()[0])
                results[str(owner)] = {
                    "summaryCount": summary_count,
                    "foreignMarkerCount": foreign_marker_count,
                }
            return results
    finally:
        connection.close()


async def run(args: argparse.Namespace, report: dict) -> bool:
    if not os.environ.get("MYSQL_PASSWORD"):
        raise RuntimeError("Set MYSQL_PASSWORD before creating evaluation users")
    run_id = secrets.token_hex(4)
    conversation_id = "mem_eval_" + run_id
    marker_a, marker_b = "MEM_A_" + run_id, "MEM_B_" + run_id
    parsed = urlsplit(args.base_url)
    ws_url = urlunsplit(("wss" if parsed.scheme == "https" else "ws", parsed.netloc, "/ws/ai/rag-chat", "", ""))
    report.update({"runId": run_id, "conversationId": conversation_id, "users": [], "chats": []})

    user_a, name_a, token_a = create_user(args.base_url, run_id, 1)
    report["users"].append({"id": user_a, "username": name_a})
    user_b, name_b, token_b = create_user(args.base_url, run_id, 2)
    report["users"].append({"id": user_b, "username": name_b})

    prompts = (
        ("A-first", token_a, f"我的 iPhone 13 电池健康85%，标记{marker_a}，发布时应说明什么？"),
        ("B-first", token_b, f"我的二手相机快门3万次，标记{marker_b}，发布时应说明什么？"),
        ("A-reconnect", token_a, "继续刚才的手机问题，标题怎么写？"),
    )
    for label, token, question in prompts:
        result = await chat(ws_url, token, conversation_id, question, args.timeout_sec)
        report["chats"].append({"label": label, **result})
        print(f"[OK] {label} events={','.join(result['events'])} elapsedMs={result['elapsedMs']}")

    report["database"] = check_mysql(user_a, user_b, conversation_id, (marker_a, marker_b))
    passed = all(
        row["summaryCount"] >= 1 and row["foreignMarkerCount"] == 0
        for row in report["database"].values()
    )
    return passed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--timeout-sec", type=float, default=90.0)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()
    report: dict = {"status": "ERROR"}
    passed = False
    try:
        passed = asyncio.run(run(args, report))
        report["status"] = "PASS" if passed else "RISK"
    except Exception as error:
        report["status"] = "ERROR"
        report["error"] = str(error)
        print(f"[ERROR] {error}")
    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"memory_scope_live_eval_report_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"status={report['status']} report={output}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
