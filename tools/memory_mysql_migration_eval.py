#!/usr/bin/env python3
"""Evaluate and optionally apply the fail-closed MySQL L2 memory migration.

The default mode is read-only. ``--isolated-eval`` creates and removes a
random temporary database. Production classification requires both ``--apply``
and ``--confirm-classification`` and always creates a full backup table first.
Memory content is never written to the report.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
from datetime import datetime
from pathlib import Path

import pymysql


COLUMNS = {
    "source_event_id": "varchar(128) DEFAULT NULL",
    "idempotency_key": "char(64) COLLATE utf8mb4_bin DEFAULT NULL",
    "payload_hash": "char(64) COLLATE utf8mb4_bin DEFAULT NULL",
    "source_type": "varchar(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
    "assertion_status": "varchar(32) NOT NULL DEFAULT 'INFERRED'",
    "scope_type": "varchar(32) NOT NULL DEFAULT 'USER'",
    "scope_key": "varchar(128) COLLATE utf8mb4_bin NOT NULL DEFAULT 'USER'",
    "task_key": "varchar(128) COLLATE utf8mb4_bin DEFAULT NULL",
    "slot_key": "varchar(128) COLLATE utf8mb4_bin NOT NULL DEFAULT ''",
    "revision": "bigint NOT NULL DEFAULT 0",
    "status": "varchar(32) NOT NULL DEFAULT 'QUARANTINED'",
    "supersedes_id": "bigint DEFAULT NULL",
    "valid_from": "datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)",
    "valid_until": "datetime(6) DEFAULT NULL",
    "deleted_at": "datetime(6) DEFAULT NULL",
}

INDEXES = {
    "uk_ai_l2_owner_idempotency": (True, "`user_id`, `idempotency_key`"),
    "idx_ai_l2_owner_type_status_time": (False, "`user_id`, `memory_type`, `status`, `updated_time`"),
    "idx_ai_l2_owner_session_type_revision": (False, "`user_id`, `session_id`, `memory_type`, `revision`"),
    "idx_ai_l2_validity": (False, "`status`, `valid_until`"),
    "idx_ai_l2_supersedes": (False, "`supersedes_id`"),
}

SLOT_COLUMNS = {
    "slot_key": "varchar(128) COLLATE utf8mb4_bin NOT NULL DEFAULT ''",
}

SLOT_INDEXES = {
    "uk_ai_memory_slot_scope_v2": (
        True,
        "`owner_user_id`, `scope_type`, `scope_key`, `memory_type`, `slot_key`",
    ),
}


def execute_sql_file(cursor, path: Path) -> None:
    sql = path.read_text(encoding="utf-8")
    for statement in (part.strip() for part in sql.split(";")):
        if statement:
            cursor.execute(statement)


def connection(database: str | None = None, autocommit: bool = True):
    password = os.environ.get("MYSQL_PASSWORD")
    if not password:
        raise RuntimeError("MYSQL_PASSWORD is required")
    return pymysql.connect(
        host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
        port=int(os.environ.get("MYSQL_PORT", "3306")),
        user=os.environ.get("MYSQL_USERNAME", "root"),
        password=password,
        database=database,
        charset="utf8mb4",
        autocommit=autocommit,
        connect_timeout=5,
        read_timeout=20,
        write_timeout=20,
    )


def safe_identifier(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_]+", value):
        raise ValueError(f"unsafe SQL identifier: {value!r}")
    return value


def table_exists(cursor, table: str) -> bool:
    cursor.execute(
        "SELECT COUNT(1) FROM information_schema.tables "
        "WHERE table_schema=DATABASE() AND table_name=%s",
        (table,),
    )
    return cursor.fetchone()[0] == 1


def upgrade_schema(cursor) -> dict:
    if not table_exists(cursor, "ai_l2_memory_record"):
        raise RuntimeError("ai_l2_memory_record does not exist")
    added_columns: list[str] = []
    added_indexes: list[str] = []
    added_slot_columns: list[str] = []
    added_slot_indexes: list[str] = []
    removed_slot_indexes: list[str] = []
    for name, definition in COLUMNS.items():
        cursor.execute(
            "SELECT COUNT(1) FROM information_schema.columns "
            "WHERE table_schema=DATABASE() AND table_name='ai_l2_memory_record' AND column_name=%s",
            (name,),
        )
        if cursor.fetchone()[0] == 0:
            cursor.execute(f"ALTER TABLE `ai_l2_memory_record` ADD COLUMN `{name}` {definition}")
            added_columns.append(name)
    for name, (unique, columns) in INDEXES.items():
        cursor.execute(
            "SELECT COUNT(1) FROM information_schema.statistics "
            "WHERE table_schema=DATABASE() AND table_name='ai_l2_memory_record' AND index_name=%s",
            (name,),
        )
        if cursor.fetchone()[0] == 0:
            qualifier = "UNIQUE " if unique else ""
            cursor.execute(f"CREATE {qualifier}INDEX `{name}` ON `ai_l2_memory_record` ({columns})")
            added_indexes.append(name)
    if not table_exists(cursor, "ai_memory_slot"):
        cursor.execute(
            """
            CREATE TABLE ai_memory_slot (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                owner_user_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
                scope_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
                scope_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
                memory_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
                slot_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
                current_record_id BIGINT DEFAULT NULL,
                revision BIGINT NOT NULL DEFAULT 0,
                created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                KEY idx_ai_memory_slot_record (current_record_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
            """
        )
        added_slot_columns.append("__table__")
    for name, definition in SLOT_COLUMNS.items():
        cursor.execute(
            "SELECT COUNT(1) FROM information_schema.columns "
            "WHERE table_schema=DATABASE() AND table_name='ai_memory_slot' AND column_name=%s",
            (name,),
        )
        if cursor.fetchone()[0] == 0:
            cursor.execute(f"ALTER TABLE ai_memory_slot ADD COLUMN `{name}` {definition}")
            added_slot_columns.append(name)
    for name, (unique, columns) in SLOT_INDEXES.items():
        cursor.execute(
            "SELECT COUNT(1) FROM information_schema.statistics "
            "WHERE table_schema=DATABASE() AND table_name='ai_memory_slot' AND index_name=%s",
            (name,),
        )
        if cursor.fetchone()[0] == 0:
            qualifier = "UNIQUE " if unique else ""
            cursor.execute(f"CREATE {qualifier}INDEX `{name}` ON ai_memory_slot ({columns})")
            added_slot_indexes.append(name)
    cursor.execute(
        "SELECT COUNT(1) FROM information_schema.statistics "
        "WHERE table_schema=DATABASE() AND table_name='ai_memory_slot' "
        "AND index_name='uk_ai_memory_slot_scope'"
    )
    if cursor.fetchone()[0] > 0:
        cursor.execute("DROP INDEX uk_ai_memory_slot_scope ON ai_memory_slot")
        removed_slot_indexes.append("uk_ai_memory_slot_scope")
    return {
        "addedColumns": added_columns,
        "addedIndexes": added_indexes,
        "addedSlotColumns": added_slot_columns,
        "addedSlotIndexes": added_slot_indexes,
        "removedSlotIndexes": removed_slot_indexes,
    }


def classification_counts(cursor) -> dict[str, int]:
    cursor.execute(
        """
        SELECT CASE
                 WHEN r.user_id REGEXP '^[0-9]+$'
                      AND u.id IS NOT NULL AND u.status = 1 AND u.is_deleted = 0 THEN 'VERIFIED_OWNER'
                 WHEN TRIM(r.user_id) = '' THEN 'EMPTY_OWNER'
                 WHEN BINARY r.user_id = BINARY COALESCE(r.session_id, '') THEN 'OWNER_EQUALS_SESSION'
                 WHEN r.user_id REGEXP '^[0-9]+$' THEN 'UNKNOWN_NUMERIC_OWNER'
                 ELSE 'UNKNOWN_TEXT_OWNER'
               END owner_class,
               COUNT(1)
        FROM ai_l2_memory_record r
        LEFT JOIN ums_user u
          ON CAST(CASE WHEN r.user_id REGEXP '^[0-9]+$' THEN r.user_id ELSE NULL END AS UNSIGNED) = u.id
        GROUP BY owner_class
        """
    )
    return {owner_class: int(count) for owner_class, count in cursor.fetchall()}


def classify_legacy(cursor) -> None:
    cursor.execute(
        """
        UPDATE ai_l2_memory_record r
        LEFT JOIN ums_user u
          ON CAST(CASE WHEN r.user_id REGEXP '^[0-9]+$' THEN r.user_id ELSE NULL END AS UNSIGNED) = u.id
        SET r.source_type = 'LEGACY_UNKNOWN',
            r.assertion_status = 'REPORTED',
            r.scope_type = CASE WHEN r.memory_type = 'SESSION_SUMMARY' THEN 'SESSION' ELSE 'USER' END,
            r.scope_key = CASE
                WHEN r.memory_type = 'SESSION_SUMMARY' AND COALESCE(r.session_id, '') <> '' THEN r.session_id
                ELSE 'USER'
            END,
            r.slot_key = COALESCE(r.slot_key, ''),
            r.revision = COALESCE(r.revision, 0),
            r.status = CASE
                WHEN r.user_id REGEXP '^[0-9]+$'
                     AND u.id IS NOT NULL AND u.status = 1 AND u.is_deleted = 0 THEN 'ACTIVE'
                ELSE 'QUARANTINED'
            END,
            r.valid_from = COALESCE(r.valid_from, r.created_time, CURRENT_TIMESTAMP(6)),
            r.deleted_at = NULL
        WHERE r.source_type = 'LEGACY_UNKNOWN'
           OR r.source_type IS NULL
        """
    )


def status_counts(cursor) -> dict[str, int]:
    cursor.execute("SELECT status, COUNT(1) FROM ai_l2_memory_record GROUP BY status ORDER BY status")
    return {str(status): int(count) for status, count in cursor.fetchall()}


def inspect(database: str) -> dict:
    with connection(database) as conn, conn.cursor() as cursor:
        cursor.execute("SELECT COUNT(1) FROM ai_l2_memory_record")
        total = int(cursor.fetchone()[0])
        cursor.execute(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema=DATABASE() AND table_name='ai_l2_memory_record'"
        )
        columns = {row[0] for row in cursor.fetchall()}
        report = {
            "database": database,
            "readOnly": True,
            "totalRows": total,
            "classification": classification_counts(cursor),
            "missingColumns": sorted(set(COLUMNS) - columns),
        }
        if "status" in columns:
            report["statusCounts"] = status_counts(cursor)
        return report


def apply_to_database(database: str) -> dict:
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = safe_identifier(f"ai_l2_memory_record_backup_{stamp}")
    with connection(database, autocommit=False) as conn, conn.cursor() as cursor:
        cursor.execute("SELECT COUNT(1) FROM ai_l2_memory_record")
        before = int(cursor.fetchone()[0])
        cursor.execute(f"CREATE TABLE `{backup}` LIKE `ai_l2_memory_record`")
        cursor.execute(f"INSERT INTO `{backup}` SELECT * FROM `ai_l2_memory_record`")
        cursor.execute(f"SELECT COUNT(1) FROM `{backup}`")
        backup_count = int(cursor.fetchone()[0])
        if backup_count != before:
            conn.rollback()
            raise RuntimeError(f"backup row count mismatch: {backup_count} != {before}")
        conn.commit()
        first = upgrade_schema(cursor)
        second = upgrade_schema(cursor)
        classify_legacy(cursor)
        conn.commit()
        cursor.execute("SELECT COUNT(1) FROM ai_l2_memory_record")
        after = int(cursor.fetchone()[0])
        statuses = status_counts(cursor)
        if after != before:
            raise RuntimeError(f"row count changed during migration: {before} -> {after}")
        if any(second[key] for key in (
            "addedColumns", "addedIndexes", "addedSlotColumns",
            "addedSlotIndexes", "removedSlotIndexes",
        )):
            raise RuntimeError("schema migration is not idempotent")
        return {
            "database": database,
            "backupTable": backup,
            "beforeRows": before,
            "backupRows": backup_count,
            "afterRows": after,
            "firstUpgrade": first,
            "secondUpgrade": second,
            "statusCounts": statuses,
            "dataRetained": before == backup_count == after,
            "unknownExcludedFromActive": statuses.get("QUARANTINED", 0) > 0,
        }


def isolated_eval() -> dict:
    database = safe_identifier("memory_migration_eval_" + secrets.token_hex(4))
    admin = connection()
    try:
        with admin.cursor() as cursor:
            cursor.execute(f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
        with connection(database) as conn, conn.cursor() as cursor:
            init_sql = Path("src/main/resources/sql/memory_l2_mysql_init.sql")
            execute_sql_file(cursor, init_sql)
            execute_sql_file(cursor, init_sql)
            cursor.execute(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_schema=DATABASE() AND table_name='ai_l2_memory_record'"
            )
            empty_columns = {row[0] for row in cursor.fetchall()}
            cursor.execute(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_schema=DATABASE() AND table_name='ai_memory_slot'"
            )
            slot_columns = {row[0] for row in cursor.fetchall()}
            cursor.execute(
                "SELECT index_name FROM information_schema.statistics "
                "WHERE table_schema=DATABASE() AND table_name='ai_memory_slot'"
            )
            slot_indexes = {row[0] for row in cursor.fetchall()}
            empty_init_passed = (
                not (set(COLUMNS) - empty_columns)
                and not (set(SLOT_COLUMNS) - slot_columns)
                and set(SLOT_INDEXES).issubset(slot_indexes)
            )
            cursor.execute("DROP TABLE ai_memory_slot")
            cursor.execute("DROP TABLE ai_l2_memory_record")
            cursor.execute(
                """
                CREATE TABLE ums_user (
                    id BIGINT PRIMARY KEY,
                    status TINYINT NOT NULL DEFAULT 1,
                    is_deleted TINYINT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB
                """
            )
            cursor.execute(
                """
                CREATE TABLE ai_l2_memory_record (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    user_id VARCHAR(64) NOT NULL,
                    session_id VARCHAR(64) DEFAULT '',
                    memory_type VARCHAR(32) NOT NULL,
                    content TEXT NOT NULL,
                    importance DOUBLE NOT NULL DEFAULT 0.5,
                    embedding_json JSON DEFAULT NULL,
                    metadata_json JSON DEFAULT NULL,
                    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """
            )
            cursor.execute("INSERT INTO ums_user(id, status, is_deleted) VALUES (1, 1, 0)")
            cursor.execute(
                "INSERT INTO ai_l2_memory_record(user_id,session_id,memory_type,content) VALUES "
                "('1','s1','FACT','known'),('legacy-session','legacy-session','FACT','unknown')"
            )
        result = apply_to_database(database)
        with connection(database) as conn, conn.cursor() as cursor:
            cursor.execute("SELECT COUNT(1) FROM ai_l2_memory_record WHERE status='ACTIVE'")
            active = int(cursor.fetchone()[0])
            cursor.execute("SELECT COUNT(1) FROM ai_l2_memory_record WHERE status='QUARANTINED'")
            quarantined = int(cursor.fetchone()[0])
            cursor.execute(
                "INSERT INTO ai_l2_memory_record "
                "(user_id,session_id,memory_type,content,idempotency_key,status) "
                "VALUES ('1','','FACT','first',REPEAT('a',64),'ACTIVE')"
            )
            unique_enforced = False
            try:
                cursor.execute(
                    "INSERT INTO ai_l2_memory_record "
                    "(user_id,session_id,memory_type,content,idempotency_key,status) "
                    "VALUES ('1','','FACT','duplicate',REPEAT('a',64),'ACTIVE')"
                )
            except pymysql.err.IntegrityError:
                unique_enforced = True
        result.update(
            {
                "isolatedDatabase": database,
                "activeKnownOwners": active,
                "quarantinedUnknownOwners": quarantined,
                "uniqueConstraintEnforced": unique_enforced,
                "emptyInitRepeatPassed": empty_init_passed,
                "passed": (
                    empty_init_passed
                    and active == 1
                    and quarantined == 1
                    and unique_enforced
                    and result["dataRetained"]
                ),
            }
        )
        return result
    finally:
        try:
            with admin.cursor() as cursor:
                cursor.execute(f"DROP DATABASE IF EXISTS `{database}`")
        finally:
            admin.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", default=os.environ.get("MYSQL_DATABASE", "used_market"))
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    parser.add_argument("--isolated-eval", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm-classification", action="store_true")
    args = parser.parse_args()
    if args.apply and not args.confirm_classification:
        parser.error("--apply requires --confirm-classification")
    if args.isolated_eval and args.apply:
        parser.error("choose either --isolated-eval or --apply")

    if args.isolated_eval:
        result = isolated_eval()
        mode = "isolated-eval"
    elif args.apply:
        result = apply_to_database(safe_identifier(args.database))
        result["passed"] = bool(result["dataRetained"] and result["unknownExcludedFromActive"])
        mode = "apply"
    else:
        result = inspect(safe_identifier(args.database))
        result["passed"] = not result["missingColumns"]
        mode = "dry-run"

    result["mode"] = mode
    result["checkedAt"] = datetime.now().astimezone().isoformat(timespec="seconds")
    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"memory_mysql_migration_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"written to {output}")
    return 0 if result.get("passed") else 1


if __name__ == "__main__":
    raise SystemExit(main())
