#!/usr/bin/env python3
"""Read-only preflight checks for the five-minute interview demo."""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any


@dataclass
class Check:
    name: str
    status: str
    detail: str
    latency_ms: float = 0.0


def decode_jwt_claims(token: str) -> dict[str, Any]:
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload.encode("ascii")))
    except (IndexError, ValueError, json.JSONDecodeError):
        return {}


def request_json(url: str, token: str | None, timeout: float) -> tuple[int, Any, float]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers, method="GET")
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", errors="replace")
            status = response.status
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        status = error.code
    latency_ms = (time.perf_counter() - started) * 1000
    try:
        return status, json.loads(body), latency_ms
    except json.JSONDecodeError:
        return status, body, latency_ms


def app_code(payload: Any) -> int | None:
    if not isinstance(payload, dict) or "code" not in payload:
        return None
    try:
        return int(payload["code"])
    except (TypeError, ValueError):
        return None


def add_http_check(
    checks: list[Check],
    name: str,
    url: str,
    token: str | None,
    timeout: float,
    validator,
) -> Any:
    try:
        status, payload, latency_ms = request_json(url, token, timeout)
        ok, detail = validator(status, payload)
        checks.append(Check(name, "OK" if ok else "FAIL", detail, latency_ms))
        return payload
    except (OSError, urllib.error.URLError, TimeoutError) as error:
        checks.append(Check(name, "FAIL", f"request failed: {error}"))
        return None


def success_result(status: int, payload: Any) -> tuple[bool, str]:
    code = app_code(payload)
    ok = 200 <= status < 300 and (code is None or code == 200)
    return ok, f"http={status}, appCode={code if code is not None else 'n/a'}"


def denied_result(status: int, payload: Any) -> tuple[bool, str]:
    code = app_code(payload)
    ok = status in {401, 403} or code in {401, 403}
    return ok, f"http={status}, appCode={code if code is not None else 'n/a'}"


def role_check(checks: list[Check], label: str, token: str | None, expected_role: str, strict: bool) -> None:
    if not token:
        checks.append(Check(label, "FAIL" if strict else "WARN", "token not provided"))
        return
    claims = decode_jwt_claims(token)
    role = str(claims.get("role", "")).upper()
    expires_at = int(claims.get("exp", 0) or 0)
    if role != expected_role:
        checks.append(Check(label, "FAIL", f"role={role or 'unknown'}, expected={expected_role}"))
    elif expires_at and expires_at <= int(time.time()):
        checks.append(Check(label, "FAIL", "token expired"))
    else:
        checks.append(Check(label, "OK", f"role={role}, userId={claims.get('userId', 'n/a')}"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only demo environment preflight")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--frontend-url", default="http://localhost:5173")
    parser.add_argument("--doc-id", type=int, default=19)
    parser.add_argument("--timeout-sec", type=float, default=5.0)
    parser.add_argument("--user-token", default=os.getenv("DEMO_USER_TOKEN", ""))
    parser.add_argument("--internal-token", default=os.getenv("DEMO_INTERNAL_TOKEN", ""))
    parser.add_argument("--admin-token", default=os.getenv("DEMO_ADMIN_TOKEN", ""))
    parser.add_argument("--strict", action="store_true", help="Treat missing role tokens as failures")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    frontend_url = args.frontend_url.rstrip("/")
    checks: list[Check] = []

    role_check(checks, "USER token", args.user_token, "USER", args.strict)
    role_check(checks, "INTERNAL token", args.internal_token, "INTERNAL", args.strict)
    role_check(checks, "ADMIN token", args.admin_token, "ADMIN", args.strict)

    add_http_check(
        checks,
        "frontend",
        frontend_url,
        None,
        args.timeout_sec,
        lambda status, payload: (200 <= status < 400, f"http={status}"),
    )
    add_http_check(
        checks,
        "backend health",
        f"{base_url}/actuator/health",
        None,
        args.timeout_sec,
        lambda status, payload: (
            status == 200 and isinstance(payload, dict) and payload.get("status") == "UP",
            f"http={status}, status={payload.get('status') if isinstance(payload, dict) else 'invalid-json'}",
        ),
    )
    add_http_check(
        checks,
        "OpenAPI",
        f"{base_url}/v3/api-docs",
        None,
        args.timeout_sec,
        lambda status, payload: (
            status == 200 and isinstance(payload, dict) and "openapi" in payload,
            f"http={status}",
        ),
    )

    if args.user_token:
        add_http_check(
            checks,
            "USER product list",
            f"{base_url}/api/products/queryProduct?pageNum=1&pageSize=1",
            args.user_token,
            args.timeout_sec,
            success_result,
        )
        add_http_check(
            checks,
            "USER internal denial",
            f"{base_url}/api/ai/knowledge/docs?limit=1",
            args.user_token,
            args.timeout_sec,
            denied_result,
        )

    if args.internal_token:
        add_http_check(
            checks,
            "INTERNAL knowledge list",
            f"{base_url}/api/ai/knowledge/docs?limit=1",
            args.internal_token,
            args.timeout_sec,
            success_result,
        )
        add_http_check(
            checks,
            "INTERNAL operation list",
            f"{base_url}/api/ai/operations?limit=1",
            args.internal_token,
            args.timeout_sec,
            success_result,
        )

        consistency_url = (
            f"{base_url}/api/ai/knowledge/storage/consistency?"
            + urllib.parse.urlencode({"docId": args.doc_id})
        )

        def consistency_result(status: int, payload: Any) -> tuple[bool, str]:
            code = app_code(payload)
            data = payload.get("data", {}) if isinstance(payload, dict) else {}
            consistent = data.get("consistent") if isinstance(data, dict) else None
            return (
                status == 200 and code == 200 and consistent is True,
                f"http={status}, appCode={code}, docId={args.doc_id}, consistent={consistent}",
            )

        add_http_check(
            checks,
            "three-store consistency",
            consistency_url,
            args.internal_token,
            max(args.timeout_sec, 10.0),
            consistency_result,
        )

    print("Demo preflight (read-only)")
    for check in checks:
        latency = f" latency={check.latency_ms:.1f}ms" if check.latency_ms else ""
        print(f"[{check.status}] {check.name}{latency} - {check.detail}")

    failures = sum(check.status == "FAIL" for check in checks)
    warnings = sum(check.status == "WARN" for check in checks)
    print(f"summary: ok={len(checks) - failures - warnings}, warn={warnings}, fail={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
