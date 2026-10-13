#!/usr/bin/env python3
"""Evaluate effective L2 recall, scale bounds and auditable lexical fallback."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import time
import urllib.parse
from datetime import datetime
from pathlib import Path

import pymysql

from memory_lifecycle_eval import create_synthetic_admin, mysql_connection, request_json


QUERY = "折叠屏转轴异响验机"


def insert_dataset(user_id: int, run_id: str, size: int) -> dict[str, int]:
    prefix = f"mem-recall-{run_id}-{size}"
    session_id = f"recall-{run_id}"
    rows: list[tuple] = []
    rows.append((
        str(user_id), session_id, "FACT",
        f"{QUERY}：检查转轴开合阻尼、异响和内屏折痕。",
        0.3, "[]", json.dumps({"eval": prefix}), prefix + "-gold",
        "EVAL", "CONFIRMED", "USER", "USER", "", "ACTIVE",
    ))
    for index in range(max(0, size - 2)):
        rows.append((
            str(user_id), session_id, "FACT",
            f"普通二手商品说明 {index}，记录外观与包装信息。",
            0.3, "[]", json.dumps({"eval": prefix}), f"{prefix}-noise-{index}",
            "EVAL", "CONFIRMED", "USER", "USER", "", "ACTIVE",
        ))
    if size > 1:
        rows.append((
            str(user_id), session_id, "FACT",
            "用户偏好非常详细的回答，与当前验机问题无关。",
            0.8, "[]", json.dumps({"eval": prefix}), prefix + "-high-importance",
            "EVAL", "CONFIRMED", "USER", "USER", "", "ACTIVE",
        ))

    with mysql_connection(autocommit=False) as connection, connection.cursor() as cursor:
        cursor.executemany(
            """
            INSERT INTO ai_l2_memory_record
                (user_id,session_id,memory_type,content,importance,embedding_json,metadata_json,
                 source_event_id,source_type,assertion_status,scope_type,scope_key,slot_key,status,
                 valid_from,created_time,updated_time)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(6),NOW(6),NOW(6))
            """,
            rows,
        )
        cursor.execute(
            "SELECT id FROM ai_l2_memory_record WHERE user_id=%s AND source_event_id=%s LIMIT 1",
            (str(user_id), prefix + "-gold"),
        )
        gold_row = cursor.fetchone()
        if not gold_row:
            raise RuntimeError("Gold memory row was not inserted")
        gold_id = int(gold_row[0])
        cursor.execute(
            """
            INSERT INTO ai_l2_memory_record
                (user_id,session_id,memory_type,content,importance,embedding_json,metadata_json,
                 source_event_id,source_type,assertion_status,scope_type,scope_key,slot_key,status,
                 valid_from,created_time,updated_time)
            VALUES (%s,%s,'PREFERENCE',%s,0.8,'[]',%s,%s,'EVAL','CONFIRMED','USER','USER','',
                    'ACTIVE',NOW(6),NOW(6),NOW(6))
            """,
            (str(user_id), session_id, QUERY + " wrong type", json.dumps({"eval": prefix}), prefix + "-wrong-type"),
        )
        foreign_user = f"foreign-{run_id}"
        cursor.execute(
            """
            INSERT INTO ai_l2_memory_record
                (user_id,session_id,memory_type,content,importance,embedding_json,metadata_json,
                 source_event_id,source_type,assertion_status,scope_type,scope_key,slot_key,status,
                 valid_from,created_time,updated_time)
            VALUES (%s,%s,'FACT',%s,0.8,'[]',%s,%s,'EVAL','CONFIRMED','USER','USER','',
                    'ACTIVE',NOW(6),NOW(6),NOW(6))
            """,
            (foreign_user, session_id, QUERY + " foreign owner", json.dumps({"eval": prefix}), prefix + "-foreign"),
        )
        connection.commit()
    return {"goldId": gold_id, "prefix": prefix, "sessionId": session_id}


def recall(base_url: str, token: str, session_id: str, candidate_limit: int = 1000,
           query_text: str = QUERY, force_lexical: bool = True) -> tuple[dict, float]:
    query = urllib.parse.urlencode([
        ("sessionId", session_id),
        ("q", query_text),
        ("memoryTypes", "FACT"),
        ("topK", "5"),
        ("threshold", "0.4"),
        ("candidateLimit", str(candidate_limit)),
        ("forceLexicalFallback", str(force_lexical).lower()),
    ])
    started = time.perf_counter()
    response = request_json(
        base_url, "/api/ai/l2-memory/debug/recall?" + query, token=token, timeout=30.0,
    )
    return response, round((time.perf_counter() - started) * 1000, 2)


def cleanup(user_id: int, run_id: str) -> None:
    with mysql_connection(autocommit=False) as connection, connection.cursor() as cursor:
        cursor.execute(
            "DELETE FROM ai_l2_memory_record WHERE user_id=%s OR source_event_id LIKE %s",
            (str(user_id), f"mem-recall-{run_id}-%"),
        )
        cursor.execute(
            "DELETE FROM ums_user WHERE id=%s AND username LIKE %s",
            (user_id, "ml_%"),
        )
        connection.commit()


def evaluate_scale(base_url: str, token: str, user_id: int, run_id: str, size: int) -> dict:
    fixture = insert_dataset(user_id, run_id, size)
    response, latency_ms = recall(base_url, token, fixture["sessionId"])
    data = response.get("data") or {}
    adopted = [int(value) for value in data.get("adoptedMemoryIds") or []]
    exclusions = data.get("exclusions") or []
    high_importance_excluded = any(
        item.get("reason") == "BELOW_RELEVANCE_THRESHOLD"
        for item in exclusions
    )
    passed = (
        response.get("code") == 200
        and fixture["goldId"] in adopted
        and data.get("eligibleCandidateCount") == size
        and data.get("evaluatedCandidateCount") == size
        and data.get("candidateTruncated") is False
        and data.get("retrievalMode") == "LEXICAL_FALLBACK"
        and data.get("fallbackReason") == "FORCED_LEXICAL_FALLBACK"
        and high_importance_excluded
    )
    return {
        "size": size,
        "goldId": fixture["goldId"],
        "adoptedMemoryIds": adopted,
        "eligibleCandidateCount": data.get("eligibleCandidateCount"),
        "evaluatedCandidateCount": data.get("evaluatedCandidateCount"),
        "candidateTruncated": data.get("candidateTruncated"),
        "retrievalMode": data.get("retrievalMode"),
        "fallbackReason": data.get("fallbackReason"),
        "highImportanceIrrelevantExcluded": high_importance_excluded,
        "latencyMs": latency_ms,
        "passed": passed,
    }


def evaluate_embedding_path(base_url: str, token: str, user_id: int, run_id: str) -> dict:
    content = "eSIM 激活锁检测：发布前核验运营商锁、账户锁和网络制式。"
    marker = f"mem-recall-{run_id}-embedding"
    params = urllib.parse.urlencode({
        "sessionId": f"recall-{run_id}",
        "memoryType": "FACT",
        "content": content,
        "importance": "0.3",
        "metadataJson": json.dumps({"eval": marker}, ensure_ascii=False),
    })
    write_response = request_json(
        base_url, "/api/ai/l2-memory/debug/write?" + params,
        method="POST", token=token, timeout=30.0,
    )
    response, latency_ms = recall(
        base_url, token, f"recall-{run_id}", query_text="eSIM 激活锁检测", force_lexical=False,
    )
    data = response.get("data") or {}
    with mysql_connection() as connection, connection.cursor() as cursor:
        cursor.execute(
            "SELECT id,embedding_json FROM ai_l2_memory_record "
            "WHERE user_id=%s AND JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.eval'))=%s "
            "ORDER BY id DESC LIMIT 1",
            (str(user_id), marker),
        )
        row = cursor.fetchone()
    stored_embedding = bool(row and row[1] and str(row[1]).strip() not in ("", "[]"))
    memory_id = None if not row else int(row[0])
    adopted = [int(value) for value in data.get("adoptedMemoryIds") or []]
    passed = (
        write_response.get("code") == 200
        and response.get("code") == 200
        and stored_embedding
        and memory_id in adopted
        and data.get("retrievalMode") in ("EMBEDDING", "HYBRID")
    )
    return {
        "memoryId": memory_id,
        "storedEmbedding": stored_embedding,
        "adoptedMemoryIds": adopted,
        "retrievalMode": data.get("retrievalMode"),
        "fallbackReason": data.get("fallbackReason"),
        "latencyMs": latency_ms,
        "passed": passed,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    parser.add_argument("--require-embedding", action="store_true",
                        help="also require a real stored embedding and normal semantic recall")
    args = parser.parse_args()

    if not os.environ.get("MYSQL_PASSWORD"):
        os.environ["MYSQL_PASSWORD"] = "123456"
    run_id = secrets.token_hex(6)
    report: dict = {"runId": run_id, "status": "ERROR", "checks": {}}
    user_id = 0
    try:
        user_id, token = create_synthetic_admin(args.base_url, run_id)
        for size in (201, 1000):
            check = evaluate_scale(args.base_url, token, user_id, run_id, size)
            report["checks"][f"scale{size}"] = check
            with mysql_connection(autocommit=False) as connection, connection.cursor() as cursor:
                cursor.execute(
                    "DELETE FROM ai_l2_memory_record WHERE source_event_id LIKE %s",
                    (f"mem-recall-{run_id}-{size}%",),
                )
                connection.commit()
        if args.require_embedding:
            report["checks"]["embeddingPath"] = evaluate_embedding_path(
                args.base_url, token, user_id, run_id,
            )
        report["status"] = "PASS" if all(
            item.get("passed") for item in report["checks"].values()
        ) else "FAIL"
    except Exception as error:  # noqa: BLE001 - report exact acceptance blocker
        report["error"] = f"{type(error).__name__}: {error}"
    finally:
        if user_id:
            cleanup(user_id, run_id)
        report["finishedAt"] = datetime.now().isoformat(timespec="seconds")
        args.report_dir.mkdir(parents=True, exist_ok=True)
        output = args.report_dir / f"memory_recall_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        print(f"written to {output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
