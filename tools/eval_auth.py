from __future__ import annotations

import json
import os
import urllib.request
from typing import Any


def add_auth_args(parser) -> None:
    parser.add_argument(
        "--bearer-token",
        "--auth-token",
        dest="bearer_token",
        default=os.environ.get("EVAL_BEARER_TOKEN", os.environ.get("JWT_TOKEN", "")),
        help="JWT token without Bearer prefix. Defaults to EVAL_BEARER_TOKEN or JWT_TOKEN.",
    )
    parser.add_argument("--login-username", default=os.environ.get("EVAL_LOGIN_USERNAME", ""))
    parser.add_argument("--login-password", default=os.environ.get("EVAL_LOGIN_PASSWORD", ""))
    parser.add_argument("--login-type", default=os.environ.get("EVAL_LOGIN_TYPE", "USERNAME_PASSWORD"))


def resolve_bearer_token(args: Any, base_url: str, timeout_sec: float) -> str:
    token = getattr(args, "bearer_token", "") or ""
    if token:
        return strip_bearer_prefix(token)

    username = getattr(args, "login_username", "") or ""
    password = getattr(args, "login_password", "") or ""
    login_type = getattr(args, "login_type", "") or "USERNAME_PASSWORD"
    if not username or not password:
        return ""

    return login_for_token(base_url, username, password, login_type, timeout_sec)


def login_for_token(base_url: str, username: str, password: str, login_type: str, timeout_sec: float) -> str:
    payload = {
        "account": username,
        "password": password,
        "loginType": login_type,
    }
    # Current controller maps /Login, while security config may mention /login.
    for path in ("/api/auth/Login", "/api/auth/login"):
        try:
            resp = post_json(base_url.rstrip("/") + path, payload, timeout_sec)
            token = extract_token(resp)
            if token:
                return strip_bearer_prefix(token)
        except Exception:
            continue
    raise RuntimeError("Login failed. Check --login-username/--login-password or provide --bearer-token.")


def post_json(url: str, payload: dict[str, Any], timeout_sec: float) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
        return json.loads(resp.read().decode("utf-8"))


def extract_token(resp: dict[str, Any]) -> str:
    data = resp.get("data") if isinstance(resp, dict) else None
    if isinstance(data, dict):
        token = data.get("token")
        if isinstance(token, str) and token:
            return token
    token = resp.get("token") if isinstance(resp, dict) else None
    return token if isinstance(token, str) else ""


def strip_bearer_prefix(token: str) -> str:
    token = token.strip()
    if token.lower().startswith("bearer "):
        return token[7:].strip()
    return token
