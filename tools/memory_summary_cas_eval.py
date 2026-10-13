#!/usr/bin/env python3
"""Run a deterministic two-writer CAS check against the MySQL memory slot."""

from __future__ import annotations

import json
import secrets
import threading
from datetime import datetime
from pathlib import Path

from memory_restart_resume_eval import mysql_connection


def main() -> int:
    owner = "cas_eval_" + secrets.token_hex(6)
    session = "cas_session_" + secrets.token_hex(6)
    barrier = threading.Barrier(2)
    outcomes: list[dict] = []
    lock = threading.Lock()

    bootstrap = mysql_connection()
    try:
        with bootstrap.cursor() as cursor:
            cursor.execute(
                "INSERT INTO ai_memory_slot "
                "(owner_user_id,scope_type,scope_key,memory_type,current_record_id,revision) "
                "VALUES (%s,'SESSION',%s,'SESSION_SUMMARY',NULL,0)",
                (owner, session),
            )
        bootstrap.commit()
    finally:
        bootstrap.close()

    def writer(label: str) -> None:
        connection = mysql_connection()
        record_id = None
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "INSERT INTO ai_l2_memory_record "
                    "(user_id,session_id,memory_type,content,importance,embedding_json,metadata_json) "
                    "VALUES (%s,%s,'SESSION_SUMMARY',%s,0.8,'[]',%s)",
                    (owner, session, "SESSION_SUMMARY|writer=" + label, json.dumps({"writer": label})),
                )
                record_id = int(cursor.lastrowid)
                barrier.wait(timeout=10)
                cursor.execute(
                    "UPDATE ai_memory_slot SET current_record_id=%s,revision=revision+1 "
                    "WHERE owner_user_id=%s AND scope_type='SESSION' AND scope_key=%s "
                    "AND memory_type='SESSION_SUMMARY' AND revision=0",
                    (record_id, owner, session),
                )
                accepted = cursor.rowcount == 1
                if not accepted:
                    cursor.execute("DELETE FROM ai_l2_memory_record WHERE id=%s", (record_id,))
            connection.commit()
            with lock:
                outcomes.append({"writer": label, "accepted": accepted, "recordId": record_id})
        except Exception as error:
            connection.rollback()
            with lock:
                outcomes.append({"writer": label, "accepted": False, "error": str(error)})
        finally:
            connection.close()

    threads = [threading.Thread(target=writer, args=(label,)) for label in ("A", "B")]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=15)

    verify = mysql_connection()
    try:
        with verify.cursor() as cursor:
            cursor.execute(
                "SELECT revision,current_record_id FROM ai_memory_slot "
                "WHERE owner_user_id=%s AND scope_type='SESSION' AND scope_key=%s "
                "AND memory_type='SESSION_SUMMARY'",
                (owner, session),
            )
            slot = cursor.fetchone()
            cursor.execute(
                "SELECT id FROM ai_l2_memory_record WHERE user_id=%s AND session_id=%s "
                "AND memory_type='SESSION_SUMMARY'",
                (owner, session),
            )
            records = [int(row[0]) for row in cursor.fetchall()]
            accepted = [one for one in outcomes if one.get("accepted")]
            status = "PASS" if (
                len(outcomes) == 2
                and len(accepted) == 1
                and slot is not None
                and int(slot[0]) == 1
                and len(records) == 1
                and int(slot[1]) == records[0] == int(accepted[0]["recordId"])
            ) else "RISK"
            report = {
                "status": status,
                "writerCount": len(outcomes),
                "acceptedWriterCount": len(accepted),
                "slotRevision": int(slot[0]) if slot else None,
                "persistedSummaryCount": len(records),
                "slotPointsToWinner": bool(
                    slot and accepted and records
                    and int(slot[1]) == records[0] == int(accepted[0]["recordId"])
                ),
                "outcomes": outcomes,
            }
            cursor.execute(
                "DELETE FROM ai_memory_slot WHERE owner_user_id=%s AND scope_type='SESSION' AND scope_key=%s",
                (owner, session),
            )
            cursor.execute(
                "DELETE FROM ai_l2_memory_record WHERE user_id=%s AND session_id=%s",
                (owner, session),
            )
        verify.commit()
    finally:
        verify.close()

    output_dir = Path("eval/reports")
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / f"memory_summary_cas_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"status={report['status']} report={output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
