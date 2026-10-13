#!/usr/bin/env python3
"""Read-only owner classification for legacy L2 records. Never exports memory content."""

from __future__ import annotations

import argparse
import json
import os
from datetime import datetime
from pathlib import Path

import pymysql


AUDIT_SQL = """
SELECT r.memory_type,
       CASE
         WHEN u.id IS NOT NULL THEN 'VALID_USER'
         WHEN TRIM(r.user_id) = '' THEN 'EMPTY_OWNER'
         WHEN BINARY r.user_id = BINARY COALESCE(r.session_id, '') THEN 'OWNER_EQUALS_SESSION'
         WHEN r.user_id REGEXP '^[0-9]+$' THEN 'UNKNOWN_NUMERIC_OWNER'
         ELSE 'UNKNOWN_TEXT_OWNER'
       END AS owner_class,
       COUNT(1)
FROM ai_l2_memory_record r
LEFT JOIN ums_user u ON CAST(r.user_id AS UNSIGNED) = u.id
GROUP BY r.memory_type, owner_class
ORDER BY r.memory_type, owner_class
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()
    password = os.environ.get("MYSQL_PASSWORD")
    if not password:
        parser.error("MYSQL_PASSWORD is required")

    connection = pymysql.connect(
        host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
        port=int(os.environ.get("MYSQL_PORT", "3306")),
        user=os.environ.get("MYSQL_USERNAME", "root"),
        password=password,
        database=os.environ.get("MYSQL_DATABASE", "used_market"),
        connect_timeout=5,
        read_timeout=10,
        autocommit=True,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(AUDIT_SQL)
            counts = [
                {"memoryType": memory_type, "ownerClass": owner_class, "count": count}
                for memory_type, owner_class, count in cursor.fetchall()
            ]
            cursor.execute("SHOW INDEX FROM ai_l2_memory_record")
            indexes = sorted({(row[2], row[4]) for row in cursor.fetchall()})
            cursor.execute(
                "SELECT TABLE_NAME FROM information_schema.COLUMNS "
                "WHERE TABLE_SCHEMA=%s GROUP BY TABLE_NAME "
                "HAVING SUM(COLUMN_NAME=%s)>0 AND SUM(COLUMN_NAME=%s)>0",
                (os.environ.get("MYSQL_DATABASE", "used_market"), "session_id", "user_id"),
            )
            mapping_tables = [row[0] for row in cursor.fetchall()]
    finally:
        connection.close()

    report = {
        "checkedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "readOnly": True,
        "totalRows": sum(item["count"] for item in counts),
        "counts": counts,
        "indexes": [{"name": name, "column": column} for name, column in indexes],
        "tablesWithSessionAndUserColumns": mapping_tables,
        "note": "Unknown ownership must not be inferred from sessionId or auto-migrated.",
    }
    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"memory_legacy_audit_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for item in counts:
        print(f"{item['memoryType']} {item['ownerClass']} {item['count']}")
    print(f"tablesWithSessionAndUserColumns={mapping_tables}")
    print(f"totalRows={report['totalRows']} report={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
