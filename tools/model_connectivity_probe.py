#!/usr/bin/env python3
"""Probe configured OpenAI-compatible chat and embedding endpoints without using RAG."""

from __future__ import annotations

import argparse
import json
import os
import socket
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any


def post_json(url: str, api_key: str, payload: dict[str, Any], timeout: float) -> tuple[int, dict[str, Any], float]:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
            return response.status, json.loads(raw), (time.perf_counter() - started) * 1000
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {"error": {"message": raw[:500]}}
        return exc.code, body, (time.perf_counter() - started) * 1000


def error_message(body: dict[str, Any]) -> str:
    error = body.get("error")
    if isinstance(error, dict):
        return str(error.get("message") or error.get("code") or error)
    return str(error or body.get("message") or "unknown response")


def diagnose(status: int, message: str) -> str:
    if status == 401:
        return "API Key 无效、过期或未被服务端接受"
    if status == 403:
        return "账号无模型权限，或访问策略拒绝"
    if status in {402, 429}:
        return "额度不足或触发限流；结合响应 message 区分"
    if status == 404:
        return "Base URL、接口路径或模型名不正确"
    if status >= 500:
        return "模型服务端暂时不可用"
    return message


def probe_chat(base_url: str, api_key: str, model: str, timeout: float) -> dict[str, Any]:
    status, body, latency = post_json(
        f"{base_url}/v1/chat/completions",
        api_key,
        {
            "model": model,
            "messages": [{"role": "user", "content": "只回复 MODEL_OK"}],
            "temperature": 0,
            "max_tokens": 16,
        },
        timeout,
    )
    choices = body.get("choices") or []
    content = ""
    if choices and isinstance(choices[0], dict):
        content = str((choices[0].get("message") or {}).get("content") or "")
    passed = status == 200 and bool(content.strip())
    return {
        "kind": "chat",
        "model": model,
        "status": status,
        "latencyMs": latency,
        "passed": passed,
        "detail": f"content={content[:80]!r}" if passed else diagnose(status, error_message(body)),
    }


def probe_embedding(
        base_url: str,
        api_key: str,
        model: str,
        timeout: float,
        batch_size: int,
        input_chars: int) -> dict[str, Any]:
    seed = "二手商品知识库模型连通性测试，要求如实披露商品状态。"
    sample = (seed * ((input_chars // len(seed)) + 1))[:input_chars]
    inputs = [f"{index + 1}:{sample}" for index in range(batch_size)]
    status, body, latency = post_json(
        f"{base_url}/v1/embeddings",
        api_key,
        {"model": model, "input": inputs},
        timeout,
    )
    data = body.get("data") or []
    vector = data[0].get("embedding") if data and isinstance(data[0], dict) else None
    dimension = len(vector) if isinstance(vector, list) else 0
    passed = status == 200 and len(data) == batch_size and dimension > 0
    return {
        "kind": "embedding",
        "model": model,
        "status": status,
        "latencyMs": latency,
        "passed": passed,
        "dimension": dimension,
        "detail": (
            f"inputs={batch_size}, inputChars={input_chars}, outputs={len(data)}, dimension={dimension}"
            if passed else diagnose(status, error_message(body))
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Test chat and embedding model connectivity independently.")
    parser.add_argument(
        "--base-url",
        default=os.getenv("OPENAI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode"),
    )
    parser.add_argument("--api-key", default=os.getenv("OPENAI_API_KEY", ""))
    parser.add_argument("--chat-model", default=os.getenv("OPENAI_CHAT_MODEL", "qwen-plus"))
    parser.add_argument("--embedding-model", default=os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-v3"))
    parser.add_argument("--embedding-batch-size", type=int, default=1)
    parser.add_argument("--embedding-input-chars", type=int, default=32)
    parser.add_argument("--timeout-sec", type=float, default=20)
    parser.add_argument("--skip-chat", action="store_true")
    parser.add_argument("--skip-embedding", action="store_true")
    parser.add_argument("--report", default="eval/reports/model_connectivity_report.md")
    args = parser.parse_args()

    if not args.api_key.strip():
        print("[RISK] OPENAI_API_KEY is empty. Set it in the current terminal before running the probe.")
        return 2
    if args.embedding_batch_size < 1 or args.embedding_input_chars < 1:
        parser.error("--embedding-batch-size and --embedding-input-chars must be positive")

    base_url = args.base_url.rstrip("/")
    results: list[dict[str, Any]] = []
    try:
        if not args.skip_chat:
            results.append(probe_chat(base_url, args.api_key, args.chat_model, args.timeout_sec))
        if not args.skip_embedding:
            results.append(probe_embedding(
                base_url,
                args.api_key,
                args.embedding_model,
                args.timeout_sec,
                args.embedding_batch_size,
                args.embedding_input_chars,
            ))
    except (urllib.error.URLError, TimeoutError, socket.timeout) as exc:
        results.append({
            "kind": "network",
            "model": "-",
            "status": "-",
            "latencyMs": 0,
            "passed": False,
            "detail": f"网络连接或超时失败: {exc}",
        })

    for result in results:
        state = "OK" if result["passed"] else "RISK"
        print(
            f"[{state}] {result['kind']} model={result['model']} "
            f"status={result['status']} latency={result['latencyMs']:.1f}ms "
            f"detail={result['detail']}"
        )

    all_passed = bool(results) and all(bool(result["passed"]) for result in results)
    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Model Connectivity Report",
        "",
        f"- generatedAt: `{datetime.now().isoformat(timespec='seconds')}`",
        f"- baseUrl: `{base_url}`",
        f"- result: `{'PASS' if all_passed else 'RISK'}`",
        "- apiKey: `REDACTED`",
        "",
        "| Probe | Model | HTTP | Latency | Result | Detail |",
        "| --- | --- | ---: | ---: | --- | --- |",
    ]
    for result in results:
        detail = str(result["detail"]).replace("|", "/").replace("\n", " ")
        lines.append(
            f"| {result['kind']} | {result['model']} | {result['status']} | "
            f"{result['latencyMs']:.1f}ms | {'OK' if result['passed'] else 'RISK'} | {detail} |"
        )
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"written to {report}")
    return 0 if all_passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
