#!/usr/bin/env python3
"""Check that an explicit product switch clears old L3 product state.

Only accepts a synthetic mem_eval_* account. Reports contain no credentials or memory text.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import secrets
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

from memory_restart_resume_eval import current_state, mysql_connection, sms_login
from memory_scope_live_eval import chat


def summary(user_id: int, session_id: str) -> tuple[int, str, str, bool]:
    connection = mysql_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id, metadata_json FROM ai_l2_memory_record "
                "WHERE user_id=%s AND session_id=%s AND memory_type='SESSION_SUMMARY' "
                "ORDER BY updated_time DESC, id DESC LIMIT 1",
                (str(user_id), session_id),
            )
            row = cursor.fetchone()
            if not row:
                raise RuntimeError("No persisted summary for synthetic test session")
            kv = (json.loads(row[1] or "{}").get("kv") or {})
            return (
                int(row[0]),
                str(kv.get("brand") or ""),
                str(kv.get("model") or ""),
                "无发票" in str(kv.get("knownFacts") or ""),
            )
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--user-id", type=int, required=True)
    parser.add_argument("--existing-session", required=True)
    parser.add_argument("--timeout-sec", type=float, default=90.0)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()
    session_id = "mem_eval_switch_" + secrets.token_hex(5)
    report: dict = {"userId": args.user_id, "sessionId": session_id, "status": "ERROR"}
    try:
        phone, _, _ = current_state(args.user_id, args.existing_session)
        token = sms_login(args.base_url, phone, args.user_id)
        parsed = urlsplit(args.base_url)
        ws_url = urlunsplit(
            ("wss" if parsed.scheme == "https" else "ws", parsed.netloc, "/ws/ai/rag-chat", "", "")
        )
        first = asyncio.run(chat(
            ws_url, token, session_id, "iPhone 13 手机无发票怎么定价？", args.timeout_sec
        ))
        first_id, _, first_model, old_fact = summary(args.user_id, session_id)
        second = asyncio.run(chat(
            ws_url, token, session_id, "现在换成华为手机，帮我写标题。", args.timeout_sec
        ))
        second_id, brand, second_model, retained_old_fact = summary(args.user_id, session_id)
        report.update({
            "firstChat": first,
            "secondChat": second,
            "firstModelExpected": first_model.casefold() == "iphone 13",
            "oldFactInitiallyPresent": old_fact,
            "newSummaryCreated": second_id > first_id,
            "newBrandExpected": brand == "Huawei",
            "oldModelCleared": not second_model,
            "oldFactCleared": not retained_old_fact,
        })
        report["status"] = "PASS" if all(report[key] for key in (
            "firstModelExpected", "oldFactInitiallyPresent", "newSummaryCreated",
            "newBrandExpected", "oldModelCleared", "oldFactCleared",
        )) else "RISK"
    except Exception as error:
        report["error"] = str(error)
        print(f"[ERROR] {error}")
    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"memory_product_switch_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"status={report['status']} report={output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
