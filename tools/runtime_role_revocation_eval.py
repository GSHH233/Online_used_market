#!/usr/bin/env python3
"""Verify stale INTERNAL tokens cannot start/replay Runtime after a MySQL role downgrade."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import time
from datetime import datetime
from pathlib import Path

from memory_restart_resume_eval import mysql_connection
from runtime_identity_eval import request_app_code, request_json, sms_login


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--timeout-sec", type=float, default=120.0)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()

    phone = "139" + "".join(str(secrets.randbelow(10)) for _ in range(8))
    admin_phone = "137" + "".join(str(secrets.randbelow(10)) for _ in range(8))
    username = "runtime_revoke_" + secrets.token_hex(5)
    admin_username = "runtime_admin_" + secrets.token_hex(5)
    placeholder_password = "$2a$10$" + secrets.token_urlsafe(40)[:53]
    connection = mysql_connection()
    user_id = None
    admin_user_id = None
    run_id = None
    report: dict = {"status": "ERROR"}
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "INSERT INTO ums_user(username,password,nickname,phone,status,role,is_deleted,created_time,updated_time) "
                "VALUES (%s,%s,'Runtime撤权验收',%s,1,'INTERNAL',0,NOW(),NOW())",
                (username, placeholder_password, phone),
            )
            user_id = int(cursor.lastrowid)
            cursor.execute(
                "INSERT INTO ums_user(username,password,nickname,phone,status,role,is_deleted,created_time,updated_time) "
                "VALUES (%s,%s,'Runtime审计管理员',%s,1,'ADMIN',0,NOW(),NOW())",
                (admin_username, placeholder_password, admin_phone),
            )
            admin_user_id = int(cursor.lastrowid)
        connection.commit()

        os.environ["RUNTIME_REVOKE_PHONE"] = phone
        os.environ["RUNTIME_ADMIN_PHONE"] = admin_phone
        internal_token = sms_login(
            args.base_url, "RUNTIME_REVOKE_PHONE", user_id, "INTERNAL")
        admin_token = sms_login(
            args.base_url, "RUNTIME_ADMIN_PHONE", admin_user_id, "ADMIN")
        started = request_json(args.base_url, "POST", "/api/ai/agent/runs", internal_token, {
            "question": "检查知识库服务状态。",
            "sessionId": "runtime_revoke_" + secrets.token_hex(5),
            "maxSteps": 2,
        })
        run_id = str(started["runId"])
        deadline = time.time() + args.timeout_sec
        latest = started
        while time.time() < deadline:
            latest = request_json(args.base_url, "GET", "/api/ai/agent/runs/" + run_id, internal_token)
            if latest.get("status") in {"COMPLETED", "FAILED", "CANCELLED"}:
                break
            time.sleep(0.25)

        with connection.cursor() as cursor:
            cursor.execute("UPDATE ums_user SET role='USER',updated_time=NOW() WHERE id=%s", (user_id,))
        connection.commit()

        stale_start_code = request_app_code(
            args.base_url, "POST", "/api/ai/agent/runs", internal_token, {
                "question": "降权后不应启动内部 Runtime。",
                "sessionId": "runtime_revoke_stale",
                "maxSteps": 1,
            })
        replay_code = request_app_code(
            args.base_url, "POST", "/api/ai/agent/runs/" + run_id + "/replay", admin_token)
        audits = request_json(
            args.base_url, "GET", "/api/ai/agent/runs/" + run_id + "/access-audits?limit=20", admin_token)
        replay_denial_audited = any(
            one.get("action") == "REPLAY" and one.get("decision") == "DENY"
            for one in audits
        )
        report = {
            "status": "PASS" if (
                latest.get("status") in {"COMPLETED", "FAILED"}
                and stale_start_code == 403
                and replay_code == 403
                and replay_denial_audited
            ) else "RISK",
            "initialRunTerminal": latest.get("status") in {"COMPLETED", "FAILED"},
            "staleInternalStartDenied": stale_start_code == 403,
            "adminReplayAfterOwnerDowngradeDenied": replay_code == 403,
            "replayDenialAudited": replay_denial_audited,
        }
    except Exception as error:
        report["error"] = str(error)
        print(f"[ERROR] {error}")
    finally:
        os.environ.pop("RUNTIME_REVOKE_PHONE", None)
        os.environ.pop("RUNTIME_ADMIN_PHONE", None)
        if user_id is not None or admin_user_id is not None:
            try:
                with connection.cursor() as cursor:
                    for one_user_id in (user_id, admin_user_id):
                        if one_user_id is None:
                            continue
                        cursor.execute("DELETE FROM ai_memory_slot WHERE owner_user_id=%s", (str(one_user_id),))
                        cursor.execute("DELETE FROM ai_l2_memory_record WHERE user_id=%s", (str(one_user_id),))
                        cursor.execute("DELETE FROM ums_user WHERE id=%s", (one_user_id,))
                connection.commit()
            except Exception:
                connection.rollback()
        connection.close()

    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"runtime_role_revocation_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"status={report['status']} report={output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
