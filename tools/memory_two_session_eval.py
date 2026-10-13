#!/usr/bin/env python3
"""Compare two persisted session summaries for one synthetic memory test user.

Only mem_eval_* accounts are accepted. Credentials and memory content stay out of reports.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import secrets
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

from memory_restart_resume_eval import current_state, sms_login
from memory_scope_live_eval import chat


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--user-id", type=int, required=True)
    parser.add_argument("--existing-session", required=True)
    parser.add_argument("--timeout-sec", type=float, default=90.0)
    parser.add_argument("--report-dir", type=Path, default=Path("eval/reports"))
    args = parser.parse_args()
    new_session = "mem_eval_second_" + secrets.token_hex(5)
    report: dict = {
        "userId": args.user_id,
        "existingSession": args.existing_session,
        "newSession": new_session,
        "status": "ERROR",
    }
    try:
        phone, old_id, old_model = current_state(args.user_id, args.existing_session)
        token = sms_login(args.base_url, phone, args.user_id)
        parsed = urlsplit(args.base_url)
        ws_url = urlunsplit(
            ("wss" if parsed.scheme == "https" else "ws", parsed.netloc, "/ws/ai/rag-chat", "", "")
        )
        report["chat"] = asyncio.run(
            chat(
                ws_url,
                token,
                new_session,
                "我这次发布 MacBook Air M1 笔记本，请给一个简短标题。",
                args.timeout_sec,
            )
        )
        _, old_after_id, old_after_model = current_state(args.user_id, args.existing_session)
        _, new_id, new_model = current_state(args.user_id, new_session)
        report.update(
            {
                "oldSummaryUnchanged": old_after_id == old_id and old_after_model == old_model,
                "separateSummaryIds": old_id != new_id,
                "newSessionModelCorrect": new_model.casefold() == "macbook air m1",
                "newSessionExcludesOldModel": old_model.casefold() != new_model.casefold(),
            }
        )
        report["status"] = "PASS" if all(
            report[key] for key in (
                "oldSummaryUnchanged",
                "separateSummaryIds",
                "newSessionModelCorrect",
                "newSessionExcludesOldModel",
            )
        ) else "RISK"
    except Exception as error:
        report["error"] = str(error)
        print(f"[ERROR] {error}")
    args.report_dir.mkdir(parents=True, exist_ok=True)
    output = args.report_dir / f"memory_two_session_eval_{datetime.now():%Y%m%d-%H%M%S}.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"status={report['status']} report={output}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
