#!/usr/bin/env python3
"""Generate the reviewed MEM-06 development and holdout datasets."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


GRAPH_TEMPLATES = [
    ("用户正在学习 Java 和 K8s", "Java K8s 学习路线",
     [("Java", "K8s", "LEARNING_PATH")]),
    ("用户学习 K8s 并关注 AI Infra", "K8s AI Infra 方向",
     [("K8s", "AI Infra", "RELATED_TO")]),
    ("商品无发票，存在凭证风险", "无发票凭证风险",
     [("无发票", "凭证风险", "CAUSES_RISK")]),
    ("文案包含专柜验货，存在正品承诺风险", "专柜验货承诺风险",
     [("专柜验货", "正品承诺风险", "CAUSES_RISK")]),
    ("文案包含假一赔十，存在绝对化承诺风险", "假一赔十承诺风险",
     [("假一赔十", "绝对化承诺风险", "CAUSES_RISK")]),
    ("商品型号为 RTX3060 显卡", "RTX3060 显卡型号",
     [("RTX3060", "显卡", "IS_A")]),
    ("显卡温度偏高，存在矿卡风险", "温度偏高矿卡风险",
     [("温度偏高", "矿卡风险", "CAUSES_RISK")]),
    ("提供跑分截图仍不能排除矿卡风险", "跑分截图矿卡风险",
     [("跑分截图", "矿卡风险", "CANNOT_RULE_OUT")]),
]

DEV_MEMORIES = [
    ("用户偏好 Sony WH-1000XM5 降噪耳机", "降噪耳机偏好"),
    ("用户偏好 DJI Action 4 运动相机", "运动相机偏好"),
    ("用户偏好 ThinkPad X1 办公笔记本", "办公笔记本偏好"),
    ("用户关注 Fujifilm X-T5 相机", "相机品牌偏好"),
    ("用户偏好 Xiaomi Pad 6 平板", "平板设备偏好"),
    ("用户偏好 Nintendo Switch 游戏主机", "游戏主机偏好"),
    ("用户偏好 Keychron Q1 机械键盘", "机械键盘偏好"),
    ("用户偏好 ASICS GEL 跑鞋", "跑鞋品牌偏好"),
]

HOLDOUT_MEMORIES = [
    ("用户更喜欢 Bose QuietComfort Ultra 降噪耳机", "降噪耳机选择"),
    ("用户关注 DJI Air 3 航拍无人机", "航拍设备关注点"),
    ("用户偏好 Kindle Paperwhite 阅读器", "阅读器偏好"),
    ("用户偏好 Garmin Forerunner 265 运动手表", "运动手表偏好"),
    ("用户更看重 MacBook Air 的便携性", "轻薄笔记本偏好"),
    ("用户偏好 Steam Deck 掌机", "掌机偏好"),
    ("用户关注 Sony A7C II 微单相机", "微单相机关注点"),
    ("用户偏好 Logitech MX Master 鼠标", "办公鼠标偏好"),
]


def edge_dicts(items: list[tuple[str, str, str]]) -> list[dict[str, str]]:
    return [{"from": source, "to": target, "type": edge_type}
            for source, target, edge_type in items]


def event(alias: str, event_id: str, operation: str, memory_type: str, slot: str,
          content: str, should_store: bool, owner: str, importance: float = 0.7,
          payload_alias: str | None = None) -> dict[str, Any]:
    result = {
        "alias": alias, "eventId": event_id, "operation": operation,
        "memoryType": memory_type, "slotKey": slot, "content": content,
        "importance": importance, "shouldStore": should_store, "ownerAlias": owner,
    }
    if payload_alias:
        result["payloadAlias"] = payload_alias
    return result


def graph_payload(index: int, holdout: bool, enabled: bool) -> tuple[str, str, list[dict[str, str]], list[dict[str, str]]]:
    if not enabled:
        content, query = (HOLDOUT_MEMORIES if holdout else DEV_MEMORIES)[index % 8]
        return content, query, [], []
    offset = 5 if holdout else 0
    content, query, edges = GRAPH_TEMPLATES[(index + offset) % len(GRAPH_TEMPLATES)]
    if holdout:
        content = "根据最近记录，" + content
        query = "请回忆" + query
    return content, query, edge_dicts(edges), [
        {"from": edges[0][0], "to": "动漫", "type": "UNRELATED"},
    ]


def base_case(case_id: str, split: str, category: str, index: int, user: str,
              events: list[dict[str, Any]], query: str, allowed: list[str], gold: list[str],
              forbidden: list[str], active: dict[str, str], expected_edges: list[dict[str, str]],
              forbidden_edges: list[dict[str, str]]) -> dict[str, Any]:
    return {
        "caseId": case_id, "split": split, "category": category,
        "requirementIds": ["MEM-FR-006", "MEM-AC-006"],
        "userAlias": user, "sessionAlias": f"{split.lower()}-session-{index + 1:02d}",
        "layers": ["store", "recall", "consolidation", "context", "graph"],
        "events": events,
        "query": {"text": query, "topK": 5, "threshold": 0.2},
        "allowedStoreAliases": allowed, "goldMemoryAliases": gold,
        "forbiddenMemoryAliases": forbidden, "expectedActiveValues": active,
        "context": {
            "currentInstruction": "仅使用与本轮问题相关且属于当前用户的有效记忆",
            "usefulAliases": gold, "forbiddenAliases": forbidden,
        },
        "expectedEdges": expected_edges, "forbiddenEdges": forbidden_edges,
    }


def add_noise(events: list[dict[str, Any]], case_id: str, user: str, count: int = 1) -> None:
    for n in range(count):
        events.append(event(
            f"noise-{n + 1}", f"{case_id}-N{n + 1}", "NO_STORE", "FACT",
            f"noise-{n + 1}", f"本轮临时对话噪声 {n + 1}", False, user, 0.1,
        ))


def build(split: str, per_category: int, holdout: bool) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    prefixes = {"identity": "IDE", "update": "UPD", "negation": "NEG",
                "duplicate": "DUP", "recovery": "REC", "long_history": "LON"}
    graph_counts = {"identity": 3, "update": 3, "negation": 0,
                    "duplicate": 2, "recovery": 2, "long_history": 2}
    if holdout:
        graph_counts = {"identity": 2, "update": 1, "negation": 0,
                        "duplicate": 1, "recovery": 1, "long_history": 1}
    sequence = 0
    for category in prefixes:
        for local in range(per_category):
            sequence += 1
            case_id = f"MEM-{split}-{prefixes[category]}-{sequence:03d}"
            user = "user-a" if sequence % 2 == 0 else "user-b"
            foreign = "user-b" if user == "user-a" else "user-a"
            graph_enabled = local < graph_counts[category]
            content, query, expected_edges, forbidden_edges = graph_payload(
                local + sequence, holdout, graph_enabled,
            )
            memory_type = "FACT" if (graph_enabled or local % 2 == 0) else "PREFERENCE"
            slot = f"{category}-{local + 1}"
            events: list[dict[str, Any]] = []
            allowed: list[str] = []
            gold: list[str] = []
            forbidden: list[str] = []
            active: dict[str, str] = {}

            if category == "identity":
                events.append(event("own-write", f"{case_id}-E1", "UPSERT", memory_type,
                                    slot, content, True, user, 0.8))
                events.append(event("foreign-write", f"{case_id}-E2", "UPSERT", memory_type,
                                    slot, "另一用户偏好与本题无关的复古音箱", True, foreign, 0.95))
                add_noise(events, case_id, user)
                allowed, gold, forbidden = ["own-write"], ["own-write"], ["foreign-write", "noise-1"]
                active = {slot: content}
            elif category == "update":
                old = "旧偏好：" + query + "未确认"
                events.extend([
                    event("old-value", f"{case_id}-E1", "UPSERT", memory_type, slot, old, True, user),
                    event("corrected-value", f"{case_id}-E2", "UPSERT", memory_type, slot,
                          content, True, user, 0.8),
                ])
                add_noise(events, case_id, user)
                allowed, gold, forbidden = ["corrected-value"], ["corrected-value"], ["old-value", "noise-1"]
                active = {slot: content}
            elif category == "negation":
                removed_content, removed_query = (HOLDOUT_MEMORIES if holdout else DEV_MEMORIES)[local % 8]
                source_event = f"{case_id}-E1"
                events.extend([
                    event("initial-value", source_event, "UPSERT", "PREFERENCE", slot,
                          removed_content, True, user),
                    event("delete-value", f"{case_id}-E2", "DELETE", "PREFERENCE", slot,
                          "用户明确撤回该偏好", True, user),
                    event("old-replay", source_event, "REPLAY", "PREFERENCE", slot,
                          removed_content, False, user, payload_alias="initial-value"),
                ])
                query = removed_query
                forbidden = ["initial-value", "old-replay"]
                expected_edges, forbidden_edges = [], []
            elif category == "duplicate":
                source_event = f"{case_id}-E1"
                events.append(event("first-write", source_event, "UPSERT", "PREFERENCE", slot,
                                    content, True, user, 0.8))
                for replay in range(1, 20):
                    events.append(event(f"same-replay-{replay}", source_event, "REPLAY", "PREFERENCE",
                                        slot, content, False, user, 0.8, "first-write"))
                allowed, gold = ["first-write"], ["first-write"]
                forbidden = [f"same-replay-{value}" for value in range(1, 20)]
                active = {slot: content}
            elif category == "recovery":
                source_event = f"{case_id}-E1"
                events.extend([
                    event("before-restart", source_event, "UPSERT", memory_type, slot,
                          content, True, user, 0.8),
                    event("after-restart-replay", source_event, "REPLAY", memory_type, slot,
                          content, False, user, 0.8, "before-restart"),
                ])
                allowed, gold, forbidden = ["before-restart"], ["before-restart"], ["after-restart-replay"]
                active = {slot: content}
            else:
                add_noise(events, case_id, user, 20)
                events.append(event("long-history-gold", f"{case_id}-E1", "UPSERT", memory_type,
                                    slot, content, True, user, 0.8))
                allowed, gold = ["long-history-gold"], ["long-history-gold"]
                forbidden = [f"noise-{value}" for value in range(1, 21)]
                active = {slot: content}

            cases.append(base_case(
                case_id, split, category, sequence - 1, user, events, query,
                allowed, gold, forbidden, active, expected_edges, forbidden_edges,
            ))
    return cases


def main() -> None:
    output_dir = Path("eval/datasets")
    output_dir.mkdir(parents=True, exist_ok=True)
    datasets = {
        "memory_quality_development_dataset.json": build("DEV", 8, False),
        "memory_quality_holdout_dataset.json": build("HOLDOUT", 4, True),
    }
    for name, cases in datasets.items():
        (output_dir / name).write_text(
            json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
        )
        print(f"written {name}: {len(cases)} cases")


if __name__ == "__main__":
    main()
