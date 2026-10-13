#!/usr/bin/env python3
"""Verify persisted identity propagation into asynchronous Agent Runtime execution.

Requires INTERNAL_PHONE, MYSQL_PASSWORD and optional REDIS_PASSWORD. Secrets,
phone numbers, SMS codes and JWTs are never reported.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import time
from datetime import datetime
from pathlib import Path

import redis

from memory_restart_resume_eval import mysql_connection
from memory_scope_live_eval import post_json


def sms_login(base_url: str, phone_env: str, expected_user_id: int, expected_role: str) -> str:
    phone = os.environ.get(phone_env, "").strip()
    if not phone:
        raise RuntimeError(f"{phone_env} is required")
    client = redis.Redis(
        host=os.environ.get("REDIS_HOST", "127.0.0.1"),
        port=int(os.environ.get("REDIS_PORT", "6379")),
        password=os.environ.get("REDIS_PASSWORD") or None,
        db=int(os.environ.get("REDIS_DATABASE", "5")),
        socket_connect_timeout=3,
        socket_timeout=3,
    )
    client.ping()
    code = client.get("login:sms:code:" + phone)
    if not code:
        post_json(base_url, "/api/auth/sms/send", {"phone": phone})
        code = client.get("login:sms:code:" + phone)
    if not code:
        raise RuntimeError("Local SMS test code was not found in Redis")
    login = post_json(base_url, "/api/auth/sms/login", {"phone": phone, "code": code.decode()})
    if int(login["userId"]) != expected_user_id or str(login.get("role")) != expected_role:
        raise RuntimeError(f"SMS login did not return the expected {expected_role} identity")
    return str(login["token"])


def local_test_token(user_id: int, username: str, role: str) -> str:
    secret = os.environ.get("JWT_SECRET", "").strip()
    if not secret:
        return ""
    encode = lambda raw: base64.urlsafe_b64encode(raw).decode().rstrip("=")
    now = int(time.time())
    header = encode(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode())
    payload = encode(json.dumps({
        "userId": user_id,
        "username": username,
        "role": role,
        "iat": now,
        "exp": now + 3600,
    }, separators=(",", ":")).encode())
    signature = encode(hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest())
    return f"{header}.{payload}.{signature}"


def test_token(
        base_url: str,
        token_env: str,
        phone_env: str,
        expected_user_id: int,
        username: str,
        expected_role: str) -> str:
    token = os.environ.get(token_env, "").strip()
    if token:
        return token
    token = local_test_token(expected_user_id, username, expected_role)
    if token:
        return token
    return sms_login(base_url, phone_env, expected_user_id, expected_role)


def request_json(base_url: str, method: str, path: str, token: str, body: dict | None = None) -> dict | list:
    import urllib.error
    import urllib.request

    headers = {"Authorization": "Bearer " + token}
    payload = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(base_url.rstrip("/") + path, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{path}: HTTP {error.code}") from error
    if int(result.get("code") or 0) != 200:
        raise RuntimeError(f"{path}: appCode={result.get('code')} message={result.get('message')}")
    return result.get("data") or {}


def request_app_code(
        base_url: str,
        method: str,
        path: str,
        token: str,
        body: dict | None = None) -> int:
    import urllib.error
    import urllib.request

    payload = None
    headers = {"Authorization": "Bearer " + token}
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=payload,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        try:
            result = json.loads(error.read().decode("utf-8"))
        except Exception:
            return error.code
    return int(result.get("code") or 0)


def summary_state(user_id: int, session_id: str) -> dict:
    connection = mysql_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT COUNT(*) FROM ai_l2_memory_record "
                "WHERE user_id=%s AND session_id=%s AND memory_type='SESSION_SUMMARY'",
                (str(user_id), session_id),
            )
            own = int(cursor.fetchone()[0])
            cursor.execute(
                "SELECT COUNT(*) FROM ai_l2_memory_record "
                "WHERE user_id<>%s AND session_id=%s AND memory_type='SESSION_SUMMARY'",
                (str(user_id), session_id),
            )
            foreign = int(cursor.fetchone()[0])
            cursor.execute(
                "SELECT s.revision, s.current_record_id, r.user_id, r.session_id, r.memory_type "
                "FROM ai_memory_slot s "
                "LEFT JOIN ai_l2_memory_record r ON r.id=s.current_record_id "
                "WHERE s.owner_user_id=%s AND s.scope_type='SESSION' "
                "AND s.scope_key=%s AND s.memory_type='SESSION_SUMMARY'",
                (str(user_id), session_id),
            )
            slot = cursor.fetchone()
            return {
                "own": own,
                "foreign": foreign,
                "slotRevision": int(slot[0]) if slot else 0,
                "slotCurrentRecordId": int(slot[1]) if slot and slot[1] is not None else None,
                "slotPointsToOwnedSummary": bool(
                    slot
                    and str(slot[2]) == str(user_id)
                    and str(slot[3]) == session_id
                    and slot[4] == "SESSION_SUMMARY"
                ),
            }
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--expected-user-id", type=int, required=True)
    parser.add_argument("--timeout-sec", type=float, default=90.0)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()
    session_id = "runtime_identity_" + secrets.token_hex(6)
    report: dict = {"expectedUserId": args.expected_user_id, "sessionId": session_id, "status": "ERROR"}
    try:
        token = test_token(
            args.base_url, "INTERNAL_BEARER_TOKEN", "INTERNAL_PHONE",
            args.expected_user_id, "buyer_demo", "INTERNAL")
        started = request_json(args.base_url, "POST", "/api/ai/agent/runs", token, {
            "question": "二手手机无发票的发布建议是什么？",
            "sessionId": session_id,
            "maxSteps": 4,
        })
        run_id = str(started["runId"])
        deadline = time.time() + args.timeout_sec
        latest = started
        while time.time() < deadline:
            latest = request_json(args.base_url, "GET", "/api/ai/agent/runs/" + run_id, token)
            if latest.get("status") in {"COMPLETED", "FAILED", "CANCELLED"}:
                break
            time.sleep(0.3)
        state = summary_state(args.expected_user_id, session_id)
        admin_token = test_token(
            args.base_url, "ADMIN_BEARER_TOKEN", "ADMIN_PHONE", 1, "seller_demo", "ADMIN")
        admin_started = request_json(args.base_url, "POST", "/api/ai/agent/runs", admin_token, {
            "question": "检查知识库服务状态。",
            "sessionId": "runtime_admin_" + secrets.token_hex(4),
            "maxSteps": 2,
        })
        internal_cross_owner_code = request_app_code(
            args.base_url, "GET", "/api/ai/agent/runs/" + str(admin_started["runId"]), token)
        admin_reads_internal = request_app_code(
            args.base_url, "GET", "/api/ai/agent/runs/" + run_id, admin_token)
        access_audits = request_json(
            args.base_url,
            "GET",
            "/api/ai/agent/runs/" + str(admin_started["runId"]) + "/access-audits?limit=20",
            admin_token,
        )
        denial_audited = any(
            str(one.get("actorUserId")) == str(args.expected_user_id)
            and one.get("decision") == "DENY"
            and one.get("action") == "READ_DETAIL"
            for one in access_audits
        )
        report.update({
            "runTerminal": latest.get("status") in {"COMPLETED", "FAILED", "CANCELLED"},
            "runCompleted": latest.get("status") == "COMPLETED",
            "persistedOwnerCorrect": str(latest.get("ownerUserId") or "") == str(args.expected_user_id),
            "persistedRoleCorrect": latest.get("ownerRole") == "INTERNAL",
            "ownSummaryWritten": state["own"] >= 1,
            "foreignSummaryCount": state["foreign"],
            "slotRevision": state["slotRevision"],
            "slotCurrentRecordId": state["slotCurrentRecordId"],
            "slotPointsToOwnedSummary": state["slotPointsToOwnedSummary"],
            "internalCrossOwnerDenied": internal_cross_owner_code == 403,
            "adminCanReadInternalRun": admin_reads_internal == 200,
            "crossOwnerDenialAudited": denial_audited,
        })
        report["status"] = "PASS" if all((
            report["runCompleted"], report["persistedOwnerCorrect"], report["persistedRoleCorrect"],
            report["ownSummaryWritten"], state["foreign"] == 0,
            state["slotRevision"] >= 1, state["slotPointsToOwnedSummary"],
            report["internalCrossOwnerDenied"], report["adminCanReadInternalRun"],
            report["crossOwnerDenialAudited"],
        )) else "RISK"
    except Exception as error:
        report["error"] = str(error)
        print(f"[ERROR] {error}")
    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"runtime_identity_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"status={report['status']} report={output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
