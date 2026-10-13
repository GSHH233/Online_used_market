#!/usr/bin/env python3
"""Live check: a high-scoring FACT must not replace an exact L3 session summary.

Writes only two rows for a verified synthetic mem_eval_* user in a unique session.
The report contains boolean assertions, never tokens, phone numbers, or prompt text.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import secrets
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import websockets

from memory_restart_resume_eval import current_state, mysql_connection, sms_login


def seed(user_id: int, session_id: str) -> None:
    connection = mysql_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT username FROM ums_user WHERE id=%s", (user_id,))
            row = cursor.fetchone()
            if not row or not str(row[0]).startswith("mem_eval_"):
                raise RuntimeError("Only synthetic mem_eval_* users are allowed")
            cursor.execute(
                "INSERT INTO ai_l2_memory_record "
                "(user_id,session_id,memory_type,content,importance,embedding_json,metadata_json) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s)",
                (
                    str(user_id), session_id, "SESSION_SUMMARY",
                    "SESSION_SUMMARY|category=手机\nmodel=iPhone 13", 0.1, "[]",
                    json.dumps({"kv": {"category": "手机", "model": "iPhone 13"}}),
                ),
            )
            cursor.execute(
                "INSERT INTO ai_l2_memory_record "
                "(user_id,session_id,memory_type,content,importance,embedding_json,metadata_json) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s)",
                (
                    str(user_id), session_id, "FACT",
                    "MacBook Air M1 笔记本怎么写标题", 1.0, "[]",
                    json.dumps({"source": "memory_fact_interference_eval"}),
                ),
            )
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


async def inspect(ws_url: str, token: str, session_id: str, timeout_sec: float) -> dict:
    async with websockets.connect(ws_url, open_timeout=10) as socket:
        await socket.send(json.dumps({
            "type": "CHAT_START",
            "requestId": "mem-fact-" + secrets.token_hex(5),
            "conversationId": session_id,
            "question": "MacBook Air M1 笔记本怎么写标题？",
            "topK": 3,
            "token": token,
        }, ensure_ascii=False))
        while True:
            event = json.loads(await asyncio.wait_for(socket.recv(), timeout=timeout_sec))
            if event.get("type") == "CHAT_ERROR":
                raise RuntimeError("Chat returned CHAT_ERROR")
            if event.get("type") == "CHAT_DONE":
                payload = event.get("payload") or {}
                debug = json.loads(payload.get("memoryAssemblyDebug") or "{}")
                summary = str(debug.get("l3Summary") or "")
                injected = debug.get("l2InjectedMemories") or []
                return {
                    "chatDone": True,
                    "exactSummaryModelUsed": "model=iPhone 13" in summary,
                    "factModelNotUsedAsSummary": "MacBook Air M1" not in summary,
                    "highScoringFactInjectedSeparately": any(
                        "MacBook Air M1" in str(item) and "[FACT]" in str(item)
                        for item in injected
                    ),
                }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--user-id", type=int, required=True)
    parser.add_argument("--existing-session", required=True)
    parser.add_argument("--timeout-sec", type=float, default=90.0)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()
    session_id = "mem_eval_fact_" + secrets.token_hex(5)
    report: dict = {"userId": args.user_id, "sessionId": session_id, "status": "ERROR"}
    try:
        phone, _, _ = current_state(args.user_id, args.existing_session)
        token = sms_login(args.base_url, phone, args.user_id)
        seed(args.user_id, session_id)
        parsed = urlsplit(args.base_url)
        ws_url = urlunsplit(("wss" if parsed.scheme == "https" else "ws", parsed.netloc, "/ws/ai/rag-chat", "", ""))
        report.update(asyncio.run(inspect(ws_url, token, session_id, args.timeout_sec)))
        report["status"] = "PASS" if all(report[key] for key in (
            "chatDone", "exactSummaryModelUsed", "factModelNotUsedAsSummary",
            "highScoringFactInjectedSeparately",
        )) else "RISK"
    except Exception as error:
        report["error"] = str(error)
        print(f"[ERROR] {error}")
    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"memory_fact_interference_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"status={report['status']} report={output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
