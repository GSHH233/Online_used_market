import json
import os
import time
import uuid
from dataclasses import dataclass
from typing import List, Dict, Any

import requests


"""
L1短期记忆自动回归脚本（HTTP模式）

用途：
- 自动执行多轮问答样例
- 校验“上下文指代”是否因L1记忆得到改善
- 输出验收表（Markdown）

前提：
- 你的RAG Chat有HTTP入口（或你自行改为WS调用）
- 默认请求结构需与你项目实际接口一致

使用：
python tools/l1_memory_regression.py
"""


BASE_URL = "http://127.0.0.1:8080"
API_PATH = "/api/ai/rag-chat"  # 按你的项目实际接口改
TOP_K = 5
TIMEOUT_SEC = 30
AUTH_TOKEN = os.getenv("EVAL_BEARER_TOKEN") or os.getenv("JWT_TOKEN") or ""


@dataclass
class TurnCase:
    case_id: str
    session_id: str
    question: str
    expect_keywords: List[str]
    should_low_confidence: bool = False


def build_cases() -> List[TurnCase]:
    sid = f"l1-reg-{uuid.uuid4().hex[:8]}"
    return [
        TurnCase("L1-01", sid, "我在卖一台95新iPhone13，标题怎么写更合规？", ["95新", "标题", "合规"]),
        TurnCase("L1-02", sid, "那描述里需要强调发票吗？", ["发票", "描述"]),
        TurnCase("L1-03", sid, "如果没有原装盒子，这个要怎么说？", ["原装", "盒子", "说明"]),
        TurnCase("L1-04", sid, "那价格上一般会受影响吗？", ["价格", "影响"]),
        TurnCase("L1-05", sid, "总结一下我这个商品最容易踩雷的点", ["风险", "踩雷", "总结"]),
    ]


def call_chat(case: TurnCase) -> Dict[str, Any]:
    trace_id = f"trace-{uuid.uuid4().hex[:10]}"
    payload = {
        "sessionId": case.session_id,
        "question": case.question,
        "traceId": trace_id,
        "topK": TOP_K,
        "filter": {
            "category": "risk"
        }
    }
    url = BASE_URL + API_PATH
    headers = {}
    if AUTH_TOKEN:
        token = AUTH_TOKEN[7:].strip() if AUTH_TOKEN.lower().startswith("bearer ") else AUTH_TOKEN
        headers["Authorization"] = f"Bearer {token}"
    resp = requests.post(url, json=payload, headers=headers, timeout=TIMEOUT_SEC)
    resp.raise_for_status()
    data = resp.json()
    # 兼容常见Result包装
    if isinstance(data, dict) and "data" in data and isinstance(data["data"], dict):
        return data["data"]
    return data


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
        "answer": (result.get("answer") or "")[:200],
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
    path = f"l1_memory_regression_report_{ts}.md"

    total = len(rows)
    passed = sum(1 for r in rows if r["pass"])
    pass_rate = (passed / total * 100.0) if total else 0.0

    lines = []
    lines.append("# L1 短期记忆自动回归报告")
    lines.append("")
    lines.append(f"- 执行时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- 样本数: {total}")
    lines.append(f"- 通过数: {passed}")
    lines.append(f"- 通过率: {pass_rate:.2f}%")
    lines.append(f"- 总耗时: {elapsed_ms} ms")
    lines.append("")
    lines.append("## 验收门槛")
    lines.append("- 通过率 >= 80% 视为通过")
    lines.append("- 多轮指代问题（L1-02~L1-05）至少 3 条通过")
    lines.append("")
    lines.append("## 明细")
    lines.append("")
    lines.append("| Case | confidence_check | keyword_check | low_confidence | expected | pass | retrieval_debug |")
    lines.append("|---|---|---|---|---|---|---|")
    for r in rows:
        lines.append(
            f"| {r['case_id']} | {r['confidence_check']} | {r['keyword_check']} | {r['low_confidence']} | {r['expected_low_confidence']} | {r['pass']} | {str(r['retrieval_debug']).replace('|','/')} |"
        )

    lines.append("")
    lines.append("## 结论")
    lines.append("- 建议结合 `lowConfidence` 占比与人工抽检进一步确认记忆效果。")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    return path


def main():
    cases = build_cases()
    rows = []
    start = time.time()

    for c in cases:
        try:
            result = call_chat(c)
            rows.append(evaluate(c, result))
        except Exception as e:
            rows.append({
                "case_id": c.case_id,
                "question": c.question,
                "answer": "",
                "low_confidence": True,
                "expected_low_confidence": c.should_low_confidence,
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
    main()
