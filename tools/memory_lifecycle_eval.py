#!/usr/bin/env python3
"""Evaluate L2 slot idempotency, correction and tombstone semantics.

The script uses one existing ADMIN account only for authentication. It writes a
random isolated preference slot and removes only rows carrying that run marker.
Tokens, phone numbers and memory payloads are never persisted in the report.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import secrets
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

import pymysql


def mysql_connection(autocommit: bool = True):
    password = os.environ.get("MYSQL_PASSWORD")
    if not password:
        raise RuntimeError("MYSQL_PASSWORD is required")
    return pymysql.connect(
        host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
        port=int(os.environ.get("MYSQL_PORT", "3306")),
        user=os.environ.get("MYSQL_USERNAME", "root"),
        password=password,
        database=os.environ.get("MYSQL_DATABASE", "used_market"),
        charset="utf8mb4",
        autocommit=autocommit,
        connect_timeout=5,
        read_timeout=10,
        write_timeout=10,
    )


def request_json(base_url: str, path: str, method: str = "GET", payload: dict | None = None,
                 token: str | None = None, timeout: float = 15.0) -> dict:
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(
        base_url.rstrip("/") + path, data=data, headers=headers, method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path}: HTTP {error.code} {body[:200]}") from error


def create_synthetic_admin(base_url: str, run_id: str) -> tuple[int, str]:
    username = "ml_" + run_id
    password = secrets.token_urlsafe(12)[:18]
    phone = "137" + str(secrets.randbelow(100_000_000)).zfill(8)
    registered = request_json(
        base_url,
        "/api/auth/register",
        "POST",
        {
            "username": username,
            "password": password,
            "confirmPassword": password,
            "nickname": username,
            "phone": phone,
        },
    )
    data = registered.get("data") or {}
    if registered.get("code") != 200 or not data.get("userId"):
        raise RuntimeError(f"Synthetic user registration failed: {registered}")
    user_id = int(data["userId"])
    with mysql_connection(autocommit=False) as connection, connection.cursor() as cursor:
        cursor.execute(
            "UPDATE ums_user SET role='ADMIN' WHERE id=%s AND username=%s",
            (user_id, username),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("Synthetic account could not be promoted for isolated evaluation")
        connection.commit()
    logged_in = request_json(
        base_url,
        "/api/auth/login",
        "POST",
        {"account": username, "password": password, "loginType": "USERNAME_PASSWORD"},
    )
    login_data = logged_in.get("data") or {}
    if logged_in.get("code") != 200 or int(login_data.get("userId") or 0) != user_id:
        raise RuntimeError("Synthetic ADMIN login failed")
    if str(login_data.get("role") or "").upper() != "ADMIN":
        raise RuntimeError("Synthetic account did not receive an ADMIN token")
    return user_id, str(login_data["token"])


def write_payload(slot_key: str, event_id: str, value: str) -> dict:
    return {
        "sessionId": "memory-lifecycle-eval",
        "memoryType": "PREFERENCE",
        "content": f"PREFERENCE|writingStyle={value}",
        "importance": 0.3,
        "metadataJson": json.dumps(
            {"schema": "user_preference_v1", "key": "writingStyle", "value": value},
            ensure_ascii=False,
        ),
        "sourceEventId": event_id,
        "sourceType": "EVAL",
        "assertionStatus": "CONFIRMED",
        "scopeType": "USER",
        "scopeKey": "USER",
        "slotKey": slot_key,
    }


def cleanup(user_id: int, slot_key: str, event_prefix: str) -> None:
    with mysql_connection(autocommit=False) as connection, connection.cursor() as cursor:
        cursor.execute(
            "DELETE FROM ai_memory_slot WHERE owner_user_id=%s AND scope_type='USER' "
            "AND scope_key='USER' AND memory_type='PREFERENCE' AND slot_key=%s",
            (str(user_id), slot_key),
        )
        cursor.execute(
            "DELETE FROM ai_l2_memory_record WHERE user_id=%s AND source_event_id LIKE %s",
            (str(user_id), event_prefix + "%"),
        )
        cursor.execute(
            "DELETE FROM ums_user WHERE id=%s AND username LIKE %s",
            (user_id, "ml_%"),
        )
        connection.commit()


def database_state(user_id: int, slot_key: str, event_prefix: str) -> dict:
    with mysql_connection() as connection, connection.cursor() as cursor:
        cursor.execute(
            "SELECT status,COUNT(1) FROM ai_l2_memory_record "
            "WHERE user_id=%s AND source_event_id LIKE %s GROUP BY status ORDER BY status",
            (str(user_id), event_prefix + "%"),
        )
        statuses = {str(status): int(count) for status, count in cursor.fetchall()}
        cursor.execute(
            "SELECT current_record_id,revision FROM ai_memory_slot "
            "WHERE owner_user_id=%s AND scope_type='USER' AND scope_key='USER' "
            "AND memory_type='PREFERENCE' AND slot_key=%s",
            (str(user_id), slot_key),
        )
        slot = cursor.fetchone()
        return {
            "statusCounts": statuses,
            "slotCurrentRecordId": None if not slot or slot[0] is None else int(slot[0]),
            "slotRevision": 0 if not slot else int(slot[1]),
        }


def profile(base_url: str, token: str) -> dict:
    body = request_json(base_url, "/api/ai/l2-memory/debug/profile", token=token)
    if body.get("code") != 200:
        raise RuntimeError(f"Profile query failed: {body}")
    return body.get("data") or {}


def graph_state(base_url: str, token: str, user_id: int) -> dict:
    query = urllib.parse.urlencode({"userId": str(user_id), "limit": "100"})
    nodes = request_json(base_url, "/api/ai/memory-graph/debug/nodes?" + query, token=token)
    edges = request_json(base_url, "/api/ai/memory-graph/debug/edges?" + query, token=token)
    if nodes.get("code") != 200 or edges.get("code") != 200:
        raise RuntimeError("Memory Graph verification request failed")
    return {
        "nodeCount": len(nodes.get("data") or []),
        "edgeCount": len(edges.get("data") or []),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()

    run_id = secrets.token_hex(6)
    event_prefix = "mem-lifecycle-" + run_id
    slot_key = "eval-writing-style-" + run_id
    report: dict = {"runId": run_id, "concurrency": args.concurrency, "status": "ERROR", "checks": {}}
    user_id = 0
    try:
        user_id, token = create_synthetic_admin(args.base_url, run_id)
        first_event = event_prefix + "-create"
        first = write_payload(slot_key, first_event, "concise")
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            futures = [
                pool.submit(
                    request_json, args.base_url, "/api/ai/l2-memory/debug/slot",
                    "POST", first, token, 30.0,
                )
                for _ in range(args.concurrency)
            ]
            concurrent_results = [future.result() for future in futures]
        mutations = [one.get("data") or {} for one in concurrent_results if one.get("code") == 200]
        response_codes: dict[str, int] = {}
        for one in concurrent_results:
            code = str(one.get("code"))
            response_codes[code] = response_codes.get(code, 0) + 1
        record_ids = {one.get("recordId") for one in mutations}
        dispositions = [one.get("disposition") for one in mutations]
        report["checks"]["concurrentReplay"] = {
            "responses": len(mutations),
            "responseCodes": response_codes,
            "logicalRecordCount": len(record_ids),
            "created": dispositions.count("CREATED"),
            "replayed": dispositions.count("REPLAYED"),
            "passed": (
                len(mutations) == args.concurrency
                and len(record_ids) == 1
                and dispositions.count("CREATED") == 1
                and dispositions.count("REPLAYED") == args.concurrency - 1
            ),
        }

        conflict_payload = write_payload(slot_key, first_event, "different-payload")
        conflict = request_json(
            args.base_url, "/api/ai/l2-memory/debug/slot", "POST", conflict_payload, token,
        )
        report["checks"]["payloadConflict"] = {
            "appCode": conflict.get("code"),
            "passed": conflict.get("code") == 409,
        }

        correction = request_json(
            args.base_url, "/api/ai/l2-memory/debug/slot", "POST",
            write_payload(slot_key, event_prefix + "-correct", "structured"), token,
        )
        correction_data = correction.get("data") or {}
        corrected_profile = profile(args.base_url, token)
        corrected_raw = corrected_profile.get("rawPreferences") or {}
        report["checks"]["correction"] = {
            "disposition": correction_data.get("disposition"),
            "revision": correction_data.get("revision"),
            "profileUsesCorrectedValue": corrected_raw.get("writingStyle") == "structured",
            "passed": (
                correction.get("code") == 200
                and correction_data.get("disposition") == "UPDATED"
                and correction_data.get("revision") == 2
                and corrected_raw.get("writingStyle") == "structured"
            ),
        }

        deleted = request_json(
            args.base_url, "/api/ai/l2-memory/debug/slot", "DELETE",
            {
                "memoryType": "PREFERENCE",
                "sourceEventId": event_prefix + "-delete",
                "scopeType": "USER",
                "scopeKey": "USER",
                "slotKey": slot_key,
            },
            token,
        )
        deleted_profile = profile(args.base_url, token)
        deleted_raw = deleted_profile.get("rawPreferences") or {}
        replayed_old = request_json(
            args.base_url, "/api/ai/l2-memory/debug/slot", "POST", first, token,
        )
        after_replay_profile = profile(args.base_url, token)
        after_replay_raw = after_replay_profile.get("rawPreferences") or {}
        state = database_state(user_id, slot_key, event_prefix)
        graph = graph_state(args.base_url, token, user_id)
        report["checks"]["deleteAndNoResurrection"] = {
            "deleteDisposition": (deleted.get("data") or {}).get("disposition"),
            "oldReplayDisposition": (replayed_old.get("data") or {}).get("disposition"),
            "oldReplayStatus": (replayed_old.get("data") or {}).get("status"),
            "profileAbsentAfterDelete": deleted_raw.get("writingStyle") != "structured",
            "profileAbsentAfterOldReplay": after_replay_raw.get("writingStyle") not in {"concise", "structured"},
            "database": state,
            "graph": graph,
            "passed": (
                deleted.get("code") == 200
                and (deleted.get("data") or {}).get("disposition") == "DELETED"
                and replayed_old.get("code") == 200
                and (replayed_old.get("data") or {}).get("disposition") == "REPLAYED"
                and deleted_raw.get("writingStyle") != "structured"
                and after_replay_raw.get("writingStyle") not in {"concise", "structured"}
                and state["slotCurrentRecordId"] is None
                and state["slotRevision"] == 3
                and state["statusCounts"].get("ACTIVE", 0) == 0
                and graph["nodeCount"] == 0
                and graph["edgeCount"] == 0
            ),
        }
        report["status"] = "PASS" if all(
            check.get("passed") for check in report["checks"].values()
        ) else "RISK"
    except Exception as error:
        report["status"] = "ERROR"
        report["error"] = str(error)
        print(f"[ERROR] {error}")
    finally:
        if user_id:
            try:
                cleanup(user_id, slot_key, event_prefix)
                report["cleanup"] = "PASS"
            except Exception as cleanup_error:
                report["cleanup"] = "ERROR"
                report["cleanupError"] = str(cleanup_error)
                report["status"] = "ERROR"

    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"memory_lifecycle_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"written to {output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
