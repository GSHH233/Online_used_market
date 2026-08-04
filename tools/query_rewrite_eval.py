#!/usr/bin/env python3
"""A/B evaluate knowledge retrieval with Query Rewrite disabled and enabled."""

from __future__ import annotations

import argparse
import json
import math
import statistics
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any

from eval_auth import add_auth_args, resolve_bearer_token


NEGATION_PREFIXES = ("没有", "不能", "不得", "不会", "不", "没", "未", "无")


def constraint_covered(expected: str,
                       main_query: str,
                       structured_terms: list[str],
                       structured_negations: list[str]) -> bool:
    """Accept an exact constraint or its explicit negation/entity decomposition."""
    value = expected.strip()
    if not value:
        return True
    constraint_values = [main_query, *structured_terms, *structured_negations]
    if any(value in candidate for candidate in constraint_values if candidate):
        return True

    # The rewrite contract may represent `没有鉴定证书` as
    # negations=[`没有`] + mustKeepTerms=[`鉴定证书`].
    for prefix in NEGATION_PREFIXES:
        if not value.startswith(prefix):
            continue
        entity = value[len(prefix):].strip()
        negation_kept = any(
            prefix in candidate for candidate in [main_query, *structured_negations] if candidate
        )
        entity_kept = not entity or any(
            entity in candidate for candidate in [main_query, *structured_terms] if candidate
        )
        if negation_kept and entity_kept:
            return True
    return False


def intent_groups(case: dict[str, Any]) -> list[list[str]]:
    configured = case.get("expectedIntentGroups")
    if configured:
        return [
            [str(value).strip() for value in group if str(value).strip()]
            for group in configured
            if isinstance(group, list)
        ]
    return [[str(value).strip()] for value in case.get("expectedIntentTerms", []) if str(value).strip()]


def post_json(url: str, payload: dict[str, Any], token: str, timeout: float) -> tuple[dict[str, Any], float]:
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    return body, (time.perf_counter() - started) * 1000


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * ratio) - 1))
    return ordered[index]


def rank_of(hits: list[dict[str, Any]], gold_doc_ids: set[int]) -> int | None:
    for index, hit in enumerate(hits, start=1):
        if hit.get("docId") in gold_doc_ids:
            return index
    return None


def metrics(ranks: list[int | None], top_k: int) -> dict[str, float]:
    count = max(1, len(ranks))
    return {
        f"recall@{top_k}": sum(rank is not None and rank <= top_k for rank in ranks) / count,
        "mrr": sum(0 if rank is None else 1 / rank for rank in ranks) / count,
        f"ndcg@{top_k}": sum(
            0 if rank is None or rank > top_k else 1 / math.log2(rank + 1) for rank in ranks
        ) / count,
    }


def generated_queries(plan: dict[str, Any]) -> list[str]:
    values: list[str] = []
    main = str(plan.get("mainQuery") or "").strip()
    if main:
        values.append(main)
    for key in ("subQueries", "synonymQueries"):
        for value in plan.get(key) or []:
            text = str(value).strip()
            if text:
                values.append(text)
    return values


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/query_rewrite_eval_dataset.json")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--timeout-sec", type=float, default=20)
    parser.add_argument("--report", default="")
    add_auth_args(parser)
    args = parser.parse_args()
    token = resolve_bearer_token(args, args.base_url, args.timeout_sec)
    cases = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    endpoint = args.base_url.rstrip("/") + "/api/ai/knowledge/search"

    rows: list[dict[str, Any]] = []
    for case in cases:
        gold = {int(value) for value in case.get("goldDocIds", [])}
        common = {
            "question": case["question"],
            "topK": args.top_k,
            "mode": "hybrid",
            "filter": case.get("filter"),
        }
        row: dict[str, Any] = {"caseId": case["caseId"]}
        try:
            baseline, baseline_ms = post_json(endpoint, {**common, "rewriteEnabled": False}, token, args.timeout_sec)
            enhanced, enhanced_ms = post_json(endpoint, {**common, "rewriteEnabled": True}, token, args.timeout_sec)
            if int(baseline.get("code") or 0) != 200:
                raise RuntimeError(
                    f"baseline appCode={baseline.get('code')}, message={baseline.get('message')}"
                )
            if int(enhanced.get("code") or 0) != 200:
                raise RuntimeError(
                    f"enhanced appCode={enhanced.get('code')}, message={enhanced.get('message')}"
                )
            baseline_data = baseline.get("data") or {}
            enhanced_data = enhanced.get("data") or {}
            baseline_hits = baseline_data.get("hits") or []
            enhanced_hits = enhanced_data.get("hits") or []
            baseline_rank = rank_of(baseline_hits, gold)
            enhanced_rank = rank_of(enhanced_hits, gold)
            plan = enhanced_data.get("rewritePlan") or {}
            queries = generated_queries(plan)
            main_query = str(plan.get("mainQuery") or "")
            must_keep = [str(value) for value in case.get("mustKeepTerms", [])]
            negations = [str(value) for value in case.get("negations", [])]
            structured_terms = [str(value) for value in plan.get("mustKeepTerms") or []]
            structured_negations = [str(value) for value in plan.get("negations") or []]
            missing_must_keep = [
                term for term in must_keep
                if not constraint_covered(term, main_query, structured_terms, structured_negations)
            ]
            missing_negations = [
                term for term in negations
                if not any(
                    term in candidate
                    for candidate in [main_query, *structured_negations]
                    if candidate
                )
            ]
            faithful = not missing_must_keep
            negation_faithful = not missing_negations
            expected_intent_groups = intent_groups(case)
            query_corpus = " ".join(queries)
            expected_rewrite = bool(case.get("expectedRewrite"))
            if expected_rewrite:
                missing_intents = [
                    "/".join(group)
                    for group in expected_intent_groups
                    if group and not any(term in query_corpus for term in group)
                ]
                intent_coverage = not missing_intents
            else:
                faithful = all(term in case["question"] for term in must_keep)
                negation_faithful = all(term in case["question"] for term in negations)
                intent_coverage = True
                missing_must_keep = [] if faithful else [
                    term for term in must_keep if term not in case["question"]
                ]
                missing_negations = [] if negation_faithful else [
                    term for term in negations if term not in case["question"]
                ]
                missing_intents = []
            plan_generated = bool(plan.get("rewriteUsed"))
            rewrite_applied = bool(enhanced_data.get("rewriteUsed"))
            fallback = bool(plan.get("fallback"))
            fallback_reason = str(plan.get("fallbackReason") or "")
            debug_info = str(enhanced_data.get("debugInfo") or "")
            shadow_mode = "rewriteShadow=true" in debug_info
            branch_count = len(plan.get("subQueries") or [])
            min_sub_queries = int(case.get("expectedMinSubQueries") or 0)
            common_pass = enhanced_rank is not None and faithful and negation_faithful
            if expected_rewrite:
                passed = (
                    common_pass
                    and plan_generated
                    and rewrite_applied
                    and not fallback
                    and not shadow_mode
                    and branch_count <= 3
                    and branch_count >= min_sub_queries
                    and intent_coverage
                )
            else:
                passed = common_pass and not plan_generated and not rewrite_applied and not fallback
            failure_reasons: list[str] = []
            if enhanced_rank is None:
                failure_reasons.append(
                    f"goldMiss:expected={sorted(gold)},actual="
                    f"{[hit.get('docId') for hit in enhanced_hits]}"
                )
            if missing_must_keep:
                failure_reasons.append(f"missingTerms={missing_must_keep}")
            if missing_negations:
                failure_reasons.append(f"missingNegations={missing_negations}")
            if expected_rewrite and not plan_generated:
                failure_reasons.append("planNotGenerated")
            if expected_rewrite and not rewrite_applied:
                failure_reasons.append("rewriteNotApplied")
            if not expected_rewrite and plan_generated:
                failure_reasons.append("unexpectedPlan")
            if branch_count < min_sub_queries:
                failure_reasons.append(f"subQueries:{branch_count}<{min_sub_queries}")
            if branch_count > 3:
                failure_reasons.append(f"subQueries:{branch_count}>3")
            if missing_intents:
                failure_reasons.append(f"missingIntents={missing_intents}")
            if fallback:
                failure_reasons.append(f"fallback={fallback_reason}")
            if shadow_mode:
                failure_reasons.append("shadowMode")
            row.update(
                baselineRank=baseline_rank,
                enhancedRank=enhanced_rank,
                baselineMs=round(baseline_ms, 1),
                enhancedMs=round(enhanced_ms, 1),
                rewriteElapsedMs=enhanced_data.get("rewriteElapsedMs") or 0,
                planGenerated=plan_generated,
                rewriteUsed=rewrite_applied,
                expectedRewrite=expected_rewrite,
                trigger=enhanced_data.get("rewriteTrigger"),
                branchCount=branch_count,
                faithful=faithful,
                negationFaithful=negation_faithful,
                intentCoverage=intent_coverage,
                fallback=fallback,
                fallbackReason=fallback_reason,
                shadowMode=shadow_mode,
                recovered=baseline_rank is None and enhanced_rank is not None,
                passed=passed,
                error="; ".join(failure_reasons),
                mainQuery=main_query,
                structuredTerms=structured_terms,
                structuredNegations=structured_negations,
                subQueries=plan.get("subQueries") or [],
                enhancedDocIds=[hit.get("docId") for hit in enhanced_hits],
            )
        except Exception as exc:
            row.update(
                baselineRank=None,
                enhancedRank=None,
                baselineMs=0,
                enhancedMs=0,
                rewriteElapsedMs=0,
                planGenerated=False,
                rewriteUsed=False,
                expectedRewrite=case.get("expectedRewrite"),
                trigger="",
                branchCount=0,
                faithful=False,
                negationFaithful=False,
                intentCoverage=False,
                fallback=False,
                fallbackReason="",
                shadowMode=False,
                recovered=False,
                passed=False,
                error=f"{type(exc).__name__}: {exc}",
                mainQuery="",
                structuredTerms=[],
                structuredNegations=[],
                subQueries=[],
                enhancedDocIds=[],
            )
        rows.append(row)
        print(f"[{'OK' if row['passed'] else 'RISK'}] {row['caseId']} "
              f"base={row['baselineRank']} rewrite={row['enhancedRank']} "
              f"plan={row['planGenerated']} applied={row['rewriteUsed']} "
              f"fallback={row['fallback']} latency={row['enhancedMs']}ms "
              f"reason={row['fallbackReason'] or row['error']}")

    baseline_metrics = metrics([row["baselineRank"] for row in rows], args.top_k)
    enhanced_metrics = metrics([row["enhancedRank"] for row in rows], args.top_k)
    latencies = [float(row["enhancedMs"]) for row in rows if not row["error"]]
    expected_rewrite_rows = [row for row in rows if row["expectedRewrite"]]
    summary = {
        "cases": len(rows),
        "passed": sum(bool(row["passed"]) for row in rows),
        "baseline": baseline_metrics,
        "enhanced": enhanced_metrics,
        "noHitRecovery": sum(bool(row["recovered"]) for row in rows),
        "entityRetentionRate": (
            sum(bool(row["faithful"]) for row in expected_rewrite_rows) / max(1, len(expected_rewrite_rows))
        ),
        "negationRetentionRate": (
            sum(bool(row["negationFaithful"]) for row in expected_rewrite_rows)
            / max(1, len(expected_rewrite_rows))
        ),
        "fallbackCount": sum(bool(row["fallback"]) for row in rows),
        "shadowModeCount": sum(bool(row["shadowMode"]) for row in rows),
        "rewriteP50Ms": round(statistics.median(latencies), 1) if latencies else 0,
        "rewriteP95Ms": round(percentile(latencies, 0.95), 1),
    }
    passed = (
        summary["passed"] == summary["cases"]
        and enhanced_metrics[f"recall@{args.top_k}"] >= baseline_metrics[f"recall@{args.top_k}"]
        and enhanced_metrics["mrr"] >= baseline_metrics["mrr"] - 0.01
        and summary["entityRetentionRate"] == 1
        and summary["negationRetentionRate"] == 1
    )
    report_path = Path(args.report) if args.report else Path(
        f"query_rewrite_eval_report_{datetime.now():%Y%m%d-%H%M%S}.md"
    )
    lines = [
        "# Query Rewrite A/B Eval Report",
        "",
        f"- generatedAt: `{datetime.now().isoformat(timespec='seconds')}`",
        f"- result: `{'PASS' if passed else 'RISK'}`",
        f"- cases: `{summary['passed']}/{summary['cases']}`",
        f"- baseline: `{json.dumps(summary['baseline'], ensure_ascii=False)}`",
        f"- enhanced: `{json.dumps(summary['enhanced'], ensure_ascii=False)}`",
        f"- noHitRecovery: `{summary['noHitRecovery']}`",
        f"- entityRetentionRate: `{summary['entityRetentionRate']:.2%}`",
        f"- negationRetentionRate: `{summary['negationRetentionRate']:.2%}`",
        f"- fallbackCount: `{summary['fallbackCount']}`",
        f"- shadowModeCount: `{summary['shadowModeCount']}`",
        f"- enhanced P50/P95: `{summary['rewriteP50Ms']}ms / {summary['rewriteP95Ms']}ms`",
        "",
        "| Case | Base rank | Rewrite rank | Plan | Applied | Fallback | Shadow | Branches | Faithful | Latency | Result | Detail |",
        "| --- | ---: | ---: | --- | --- | --- | --- | ---: | --- | ---: | --- | --- |",
    ]
    for row in rows:
        lines.append(
            f"| {row['caseId']} | {row['baselineRank'] or '-'} | {row['enhancedRank'] or '-'} | "
            f"{row['planGenerated']} | {row['rewriteUsed']} | {row['fallback']} | "
            f"{row['shadowMode']} | {row['branchCount']} | "
            f"{row['faithful'] and row['negationFaithful']} | {row['enhancedMs']}ms | "
            f"{'OK' if row['passed'] else 'RISK'} | "
            f"{(row['fallbackReason'] or row['error']).replace('|', '/')} |"
        )
        if not row["passed"] and row.get("planGenerated"):
            lines.extend([
                "",
                f"- `{row['caseId']}` mainQuery: `{str(row.get('mainQuery', '')).replace('`', '')}`",
                f"- `{row['caseId']}` mustKeepTerms: `{json.dumps(row.get('structuredTerms', []), ensure_ascii=False)}`",
                f"- `{row['caseId']}` negations: `{json.dumps(row.get('structuredNegations', []), ensure_ascii=False)}`",
                f"- `{row['caseId']}` subQueries: `{json.dumps(row.get('subQueries', []), ensure_ascii=False)}`",
            ])
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"written to {report_path}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
