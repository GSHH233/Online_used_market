import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080"
WRITE_API = "/api/ai/l2-memory/debug/write"
QUERY_API = "/api/ai/l2-memory/debug/query"


def request_json(method: str, url: str, token: str | None = None):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url=url, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = resp.read().decode("utf-8")
        return json.loads(data)


def is_ok_result(payload: dict) -> bool:
    if not isinstance(payload, dict):
        return False
    code_ok = payload.get("code") == 200
    msg = str(payload.get("message", "")).strip().lower()
    message_ok = msg in {"ok", "成功", "success"}
    return code_ok and message_ok


def get_data_list(payload: dict) -> list:
    data = payload.get("data")
    return data if isinstance(data, list) else []


def write_memory(base: str, token: str, user_id: str, session_id: str, memory_type: str, importance: str, content: str):
    params = {
        "userId": user_id,
        "sessionId": session_id,
        "memoryType": memory_type,
        "importance": importance,
        "content": content,
    }
    url = f"{base}{WRITE_API}?{urllib.parse.urlencode(params)}"
    result = request_json("POST", url, token=token)
    return result


def query_memory(base: str, token: str, user_id: str, session_id: str, q: str, top_k: str, threshold: str):
    params = {
        "userId": user_id,
        "sessionId": session_id,
        "q": q,
        "topK": top_k,
        "threshold": threshold,
    }
    url = f"{base}{QUERY_API}?{urllib.parse.urlencode(params)}"
    result = request_json("GET", url, token=token)
    return result


def assert_ok(result: dict, label: str):
    if not is_ok_result(result):
        raise AssertionError(f"{label} did not return code=200,message=ok")


def case_basic_recall(base: str, token: str) -> tuple[bool, str]:
    write_result = write_memory(base, token, "u1", "s1", "FACT", "0.8", "用户偏好苹果手机，关注电池健康和发票")
    print(json.dumps(write_result, ensure_ascii=False, indent=2))
    assert_ok(write_result, "case_basic_recall write")

    query_result = query_memory(base, token, "u1", "s1", "苹果手机发票电池", "5", "0.0")
    assert_ok(query_result, "case_basic_recall query")
    hits = get_data_list(query_result)
    if len(hits) < 1:
        return False, "hits < 1"
    return True, f"hits={len(hits)}"


def case_session_isolation(base: str, token: str) -> tuple[bool, str]:
    write_result = write_memory(base, token, "u_iso", "sA", "FACT", "0.9", "只属于会话A的记忆：尼康相机快门数")
    print(json.dumps(write_result, ensure_ascii=False, indent=2))
    assert_ok(write_result, "case_session_isolation write")

    query_result = query_memory(base, token, "u_iso", "sB", "尼康相机快门数", "5", "0.0")
    assert_ok(query_result, "case_session_isolation query")
    hits = get_data_list(query_result)
    if len(hits) != 0:
        return False, f"expected 0 hits in different session, got {len(hits)}"
    return True, "different session returned 0 hit as expected"


def case_threshold_effect(base: str, token: str) -> tuple[bool, str]:
    write_result = write_memory(base, token, "u_t", "s_t", "FACT", "0.7", "用户偏好索尼耳机，关注降噪和续航")
    print(json.dumps(write_result, ensure_ascii=False, indent=2))
    assert_ok(write_result, "case_threshold_effect write")

    low_result = query_memory(base, token, "u_t", "s_t", "索尼耳机降噪", "5", "0.0")
    assert_ok(low_result, "case_threshold_effect low-threshold query")
    low_hits = len(get_data_list(low_result))

    high_result = query_memory(base, token, "u_t", "s_t", "索尼耳机降噪", "5", "0.95")
    assert_ok(high_result, "case_threshold_effect high-threshold query")
    high_hits = len(get_data_list(high_result))

    if low_hits < 1:
        return False, f"low threshold should hit >=1, got {low_hits}"
    if high_hits > low_hits:
        return False, f"high threshold hits should not exceed low threshold hits, got high={high_hits}, low={low_hits}"
    return True, f"low={low_hits}, high={high_hits}"


def case_topk_limit(base: str, token: str) -> tuple[bool, str]:
    user_id = "u_topk"
    session_id = "s_topk"
    for i in range(1, 5):
        wr = write_memory(base, token, user_id, session_id, "FACT", "0.6", f"二手笔记本测试记忆片段{i}，关键词：显卡散热屏幕")
        assert_ok(wr, f"case_topk_limit write#{i}")

    query_result = query_memory(base, token, user_id, session_id, "笔记本 显卡 散热 屏幕", "2", "0.0")
    assert_ok(query_result, "case_topk_limit query")
    hits = get_data_list(query_result)
    if len(hits) > 2:
        return False, f"topK=2 but got {len(hits)} hits"
    if len(hits) < 1:
        return False, "expected at least 1 hit"
    return True, f"hits={len(hits)}"


def main():
    parser = argparse.ArgumentParser(description="L2 memory smoke test (4 cases)")
    parser.add_argument("--base", default=BASE, help="Base URL, default: http://127.0.0.1:8080")
    parser.add_argument("--token", default=None, help="JWT token (without Bearer prefix)")
    args = parser.parse_args()

    token = args.token or os.getenv("EVAL_BEARER_TOKEN") or os.getenv("JWT_TOKEN")
    if not token:
        print("[FAIL] Missing JWT token. Use --token or set EVAL_BEARER_TOKEN/JWT_TOKEN.")
        sys.exit(2)

    cases = [
        ("Case-1 basic_recall", case_basic_recall),
        ("Case-2 session_isolation", case_session_isolation),
        ("Case-3 threshold_effect", case_threshold_effect),
        ("Case-4 topk_limit", case_topk_limit),
    ]

    passed = 0
    failed = 0

    try:
        for name, fn in cases:
            print(f"\n=== {name} ===")
            ok, detail = fn(args.base, token)
            if ok:
                passed += 1
                print(f"[PASS] {name}: {detail}")
            else:
                failed += 1
                print(f"[FAIL] {name}: {detail}")

        print(f"\nSummary: passed={passed}, failed={failed}, total={len(cases)}")
        if failed > 0:
            sys.exit(1)
        sys.exit(0)

    except urllib.error.HTTPError as e:
        body = ""
        try:
            body = e.read().decode("utf-8", errors="ignore")
        except Exception:
            pass
        print(f"[FAIL] HTTPError {e.code}: {body}")
        sys.exit(1)
    except AssertionError as e:
        print(f"[FAIL] AssertionError: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"[FAIL] Unexpected error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
