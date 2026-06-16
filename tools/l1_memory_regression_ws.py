import asyncio
import json
import os
import time
import uuid
from dataclasses import dataclass
from typing import List, Dict, Any

import websockets

"""
L1短期记忆自动回归脚本（WebSocket主通道版）

适配协议：
- 入站: CHAT_START
- 出站: CHAT_ACK / CHAT_DELTA / CHAT_SOURCE / CHAT_DONE / CHAT_ERROR

使用：
python tools/l1_memory_regression_ws.py
"""

WS_URL = "ws://127.0.0.1:8080/ws/ai/rag-chat"  # 按你的项目实际地址修改
TOP_K = 5
AUTH_TOKEN = os.getenv("EVAL_BEARER_TOKEN") or os.getenv("JWT_TOKEN") or ""


@dataclass
class TurnCase:
    case_id: str
    session_id: str
    question: str
    expect_keywords: List[str]
    should_low_confidence: bool = False


def build_cases() -> List[TurnCase]:
    sid = f"l1-ws-{uuid.uuid4().hex[:8]}"
    return [
        TurnCase("L1-WS-01", sid, "我在卖95新iPhone13，标题怎么写更合规？", ["95新", "标题", "合规"]),
        TurnCase("L1-WS-02", sid, "那描述里需要强调发票吗？", ["发票", "描述"]),
        TurnCase("L1-WS-03", sid, "如果没有原装盒子，这个要怎么说？", ["原装", "盒子", "说明"]),
        TurnCase("L1-WS-04", sid, "那价格一般会受影响吗？", ["价格", "影响"]),
        TurnCase("L1-WS-05", sid, "总结一下我这个商品最容易踩雷的点", ["风险", "踩雷", "总结"]),
    ]


async def chat_once(ws, case: TurnCase) -> Dict[str, Any]:
    request_id = f"req-{uuid.uuid4().hex[:10]}"
    payload = {
        "type": "CHAT_START",
        "sessionId": case.session_id,
        "requestId": request_id,
        "question": case.question,
        "topK": TOP_K,
        "filter": {
            "category": "risk"
        }
    }
    await ws.send(json.dumps(payload, ensure_ascii=False))

    answer_parts = []
    done_payload = None

    while True:
        raw = await ws.recv()
        msg = json.loads(raw)
        msg_type = msg.get("type")
        p = msg.get("payload") or {}

        # 如果服务端带 requestId，做一次过滤
        server_req_id = p.get("requestId")
        if server_req_id and server_req_id != request_id:
            continue

        if msg_type == "CHAT_DELTA":
            delta = p.get("delta") or p.get("text") or ""
            answer_parts.append(delta)
        elif msg_type == "CHAT_DONE":
            done_payload = p
            break
        elif msg_type == "CHAT_ERROR":
            raise RuntimeError(f"CHAT_ERROR: {p}")
        else:
            # ACK/SOURCE/PING等消息直接忽略
            pass

    if done_payload is None:
        raise RuntimeError("No CHAT_DONE received")

    # 某些实现answer只在DONE里，某些实现在DELTA拼接
    answer = done_payload.get("answer") or "".join(answer_parts)
    done_payload["answer"] = answer
    return done_payload


def evaluate(case: TurnCase, result: Dict[str, Any]) -> Dict[str, Any]:
    answer = (result.get("answer") or "").lower()
    low_conf = bool(result.get("lowConfidence", False))
    retrieval_debug = result.get("retrievalDebug", "")

    keyword_hit = sum(1 for k in case.expect_keywords if k.lower() in answer)
    keyword_ok = keyword_hit >= max(1, min(2, len(case.expect_keywords)))
    conf_ok = (low_conf == case.should_low_confidence)

    return {
        "case_id": case.case_id,
        "question": case.question,
        "answer": (result.get("answer") or "")[:220],
        "low_confidence": low_conf,
        "expected_low_confidence": case.should_low_confidence,
        "confidence_check": "PASS" if conf_ok else "FAIL",
        "keyword_hit": keyword_hit,
        "keyword_check": "PASS" if keyword_ok else "FAIL",
        "retrieval_debug": retrieval_debug,
        "pass": conf_ok and keyword_ok,
    }


def write_report(rows: List[Dict[str, Any]], elapsed_ms: int) -> str:
    ts = time.strftime("%Y%m%d-%H%M%S")
    path = f"l1_memory_regression_ws_report_{ts}.md"

    total = len(rows)
    passed = sum(1 for r in rows if r["pass"])
    pass_rate = (passed / total * 100.0) if total else 0.0

    lines = [
        "# L1 短期记忆自动回归报告（WebSocket）",
        "",
        f"- 执行时间: {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- 样本数: {total}",
        f"- 通过数: {passed}",
        f"- 通过率: {pass_rate:.2f}%",
        f"- 总耗时: {elapsed_ms} ms",
        "",
        "## 验收门槛",
        "- 通过率 >= 80% 视为通过",
        "- 多轮指代问题（第2~5轮）至少 3 条通过",
        "",
        "## 明细",
        "",
        "| Case | confidence_check | keyword_check | low_confidence | expected | pass | retrieval_debug |",
        "|---|---|---|---|---|---|---|",
    ]

    for r in rows:
        lines.append(
            f"| {r['case_id']} | {r['confidence_check']} | {r['keyword_check']} | {r['low_confidence']} | {r['expected_low_confidence']} | {r['pass']} | {str(r['retrieval_debug']).replace('|','/')} |"
        )

    lines += [
        "",
        "## 结论",
        "- 如失败集中在第2~5轮，优先检查 sessionId 是否一致、L1记忆开关是否开启、TTL是否过短。",
    ]

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    return path


async def main():
    cases = build_cases()
    rows = []
    start = time.time()

    headers = {}
    if AUTH_TOKEN:
        token = AUTH_TOKEN[7:].strip() if AUTH_TOKEN.lower().startswith("bearer ") else AUTH_TOKEN
        headers["Authorization"] = f"Bearer {token}"

    async with websockets.connect(WS_URL, max_size=10 * 1024 * 1024, additional_headers=headers or None) as ws:
        for case in cases:
            try:
                result = await chat_once(ws, case)
                rows.append(evaluate(case, result))
            except Exception as e:
                rows.append({
                    "case_id": case.case_id,
                    "question": case.question,
                    "answer": "",
                    "low_confidence": True,
                    "expected_low_confidence": case.should_low_confidence,
                    "confidence_check": "FAIL",
                    "keyword_hit": 0,
                    "keyword_check": "FAIL",
                    "retrieval_debug": f"ERROR:{type(e).__name__}:{e}",
                    "pass": False,
                })

    elapsed_ms = int((time.time() - start) * 1000)
    report = write_report(rows, elapsed_ms)

    print(json.dumps({
        "total": len(rows),
        "passed": sum(1 for r in rows if r["pass"]),
        "report": report
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
