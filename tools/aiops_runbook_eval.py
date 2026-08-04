#!/usr/bin/env python3
"""
Evaluate low-risk AIOps Runbook auto-repair flow.

Example:
  python tools/aiops_runbook_eval.py ^
    --base-url http://localhost:8080 ^
    --dataset eval/datasets/aiops_runbook_eval_dataset.json ^
    --admin-bearer-token <ADMIN_JWT> ^
    --internal-bearer-token <INTERNAL_JWT> ^
    --user-bearer-token <USER_JWT> ^
    --doc-id 50
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from eval_auth import add_auth_args, resolve_bearer_token, strip_bearer_prefix


@dataclass
class RunbookCaseResult:
    case_id: str
    ok: bool
    app_code: int
    status: str
    executed: bool | None
    verification_status: str
    denied_ok: bool
    catalog_ok: bool
    dry_run_ok: bool
    execution_ok: bool
    idempotency_ok: bool
    verification_ok: bool
    persistence_ok: bool
    event_status_ok: bool
    latency_ok: bool
    latency_ms: float
    event_id: int | None
    action_id: int | None
    detail_failures: list[str]
    error: str


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def render_template(value: Any, variables: dict[str, Any]) -> Any:
    if isinstance(value, dict):
        return {k: render_template(v, variables) for k, v in value.items()}
    if isinstance(value, list):
        return [render_template(v, variables) for v in value]
    if isinstance(value, str):
        out = value
        for key, raw in variables.items():
            out = out.replace("${" + key + "}", str(raw))
        return out
    return value


def request_json(method: str, url: str, payload: dict[str, Any] | None, timeout_sec: float, bearer_token: str) -> tuple[dict[str, Any], float]:
    headers = {}
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as ex:
        raw = ex.read().decode("utf-8")
    finally:
        latency_ms = (time.perf_counter() - start) * 1000
    return json.loads(raw), latency_ms


def token_for(auth: str, tokens: dict[str, str]) -> str:
    auth = (auth or "admin").lower()
    return tokens.get(auth, "")


def setup_case(base_url: str, case: dict[str, Any], variables: dict[str, Any], timeout_sec: float, tokens: dict[str, str]) -> list[str]:
    failures: list[str] = []
    setup = case.get("setup") if isinstance(case.get("setup"), dict) else {}
    create_event = setup.get("createEvent") if isinstance(setup.get("createEvent"), dict) else None
    if not create_event:
        return failures
    body = render_template(create_event.get("body") or {}, variables)
    token = token_for(case.get("auth", "admin"), tokens)
    if not token:
        failures.append("missingSetupToken")
        return failures
    try:
        resp, _ = request_json("POST", base_url.rstrip("/") + "/api/ai/aiops/events", body, timeout_sec, token)
    except Exception as exc:
        failures.append(f"setupError:{exc}")
        return failures
    if int(resp.get("code") or 0) != 200:
        failures.append(f"setupAppCode:{resp.get('code')}")
        return failures
    data = resp.get("data") if isinstance(resp.get("data"), dict) else {}
    event_id = data.get("id")
    save_as = str(create_event.get("saveAs") or "eventId")
    if event_id:
        variables[save_as] = event_id
    else:
        failures.append("setupMissingEventId")
        return failures
    if bool(setup.get("approveEvent", False)):
        admin_token = token_for("admin", tokens)
        if not admin_token:
            failures.append("missingApprovalToken")
            return failures
        approval_body = {"reason": str(setup.get("approvalReason") or "approved by runbook eval")}
        try:
            approval_resp, _ = request_json(
                "POST",
                base_url.rstrip("/") + f"/api/ai/aiops/events/{event_id}/approve",
                approval_body,
                timeout_sec,
                admin_token,
            )
        except Exception as exc:
            failures.append(f"approvalSetupError:{exc}")
            return failures
        if int(approval_resp.get("code") or 0) != 200:
            failures.append(f"approvalSetupAppCode:{approval_resp.get('code')}")
    return failures


def evaluate_case(base_url: str, case: dict[str, Any], variables: dict[str, Any], timeout_sec: float, tokens: dict[str, str]) -> RunbookCaseResult:
    case_id = str(case.get("caseId") or "")
    failures = setup_case(base_url, case, variables, timeout_sec, tokens)
    auth = str(case.get("auth") or "admin").lower()
    token = token_for(auth, tokens)
    if not token:
        failures.append(f"missingToken:{auth}")
        return empty_result(case_id, failures)

    method = str(case.get("method") or "POST").upper()
    path = str(case.get("path") or "")
    body = render_template(case.get("body"), variables) if "body" in case else None
    try:
        resp, latency_ms = request_json(method, base_url.rstrip("/") + path, body, timeout_sec, token)
    except Exception as exc:
        return empty_result(case_id, failures + [f"requestError:{exc}"])

    app_code = int(resp.get("code") or 0)
    data = resp.get("data")
    data_obj = data if isinstance(data, dict) else {}
    data_list = data if isinstance(data, list) else []
    expected_app_code = int(case.get("expectedAppCode") or 200)
    status = str(data_obj.get("status") or "")
    verification_status = str(data_obj.get("verificationStatus") or "")
    executed = data_obj.get("executed") if isinstance(data_obj.get("executed"), bool) else None
    event_id = as_int(data_obj.get("eventId"))
    action_id = as_int(data_obj.get("actionId"))

    if app_code != expected_app_code:
        failures.append(f"appCode:{app_code}!={expected_app_code}")

    catalog_ok = evaluate_catalog(case, data_list, failures)
    denied_ok = evaluate_denied(case, app_code, resp, failures)
    dry_run_ok = evaluate_expected_bool("expectedExecuted", executed, case, failures)
    execution_ok = evaluate_status(status, case, failures)
    idempotency_ok = evaluate_verification_for_idempotency(verification_status, case, failures)
    verification_ok = evaluate_verification(verification_status, case, failures)
    result_ok = evaluate_result(data_obj, case, failures)
    event_status_ok = evaluate_event_status(base_url, case, data_obj, variables, timeout_sec, tokens, failures)
    persistence_ok = evaluate_persistence(base_url, case, data_obj, timeout_sec, tokens, failures)
    latency_ok = evaluate_latency(case, latency_ms, failures)

    save = case.get("save") if isinstance(case.get("save"), dict) else {}
    if event_id and save.get("eventId"):
        variables[str(save["eventId"])] = event_id
    if action_id and save.get("actionId"):
        variables[str(save["actionId"])] = action_id

    ok = not failures and result_ok
    return RunbookCaseResult(
        case_id=case_id,
        ok=ok,
        app_code=app_code,
        status=status,
        executed=executed,
        verification_status=verification_status,
        denied_ok=denied_ok,
        catalog_ok=catalog_ok,
        dry_run_ok=dry_run_ok,
        execution_ok=execution_ok and result_ok,
        idempotency_ok=idempotency_ok,
        verification_ok=verification_ok,
        persistence_ok=persistence_ok,
        event_status_ok=event_status_ok,
        latency_ok=latency_ok,
        latency_ms=latency_ms,
        event_id=event_id,
        action_id=action_id,
        detail_failures=failures,
        error="" if not failures else ";".join(failures),
    )


def evaluate_catalog(case: dict[str, Any], data_list: list[Any], failures: list[str]) -> bool:
    expected = as_str_list(case.get("expectedActionCodes"))
    if not expected:
        return True
    returned = []
    for item in data_list:
        if isinstance(item, dict) and item.get("actionCode"):
            returned.append(str(item.get("actionCode")))
    missing = [x for x in expected if x not in returned]
    if missing:
        failures.append("missingActionCodes:" + ",".join(missing))
        return False
    return True


def evaluate_denied(case: dict[str, Any], app_code: int, resp: dict[str, Any], failures: list[str]) -> bool:
    if not bool(case.get("expectedDenied", False)):
        return True
    text = json.dumps(resp, ensure_ascii=False).lower()
    ok = app_code in (401, 403) or "无权" in text or "forbidden" in text or "denied" in text
    if not ok:
        failures.append("deniedNotDetected")
    return ok


def evaluate_expected_bool(key: str, actual: bool | None, case: dict[str, Any], failures: list[str]) -> bool:
    if key not in case:
        return True
    expected = bool(case.get(key))
    if actual != expected:
        failures.append(f"{key}:{actual}!={expected}")
        return False
    return True


def evaluate_status(status: str, case: dict[str, Any], failures: list[str]) -> bool:
    expected = case.get("expectedStatus")
    if expected is None:
        return True
    if status != str(expected):
        failures.append(f"status:{status}!={expected}")
        return False
    return True


def evaluate_verification(verification_status: str, case: dict[str, Any], failures: list[str]) -> bool:
    expected = case.get("expectedVerificationStatus")
    if expected is None:
        return True
    if verification_status != str(expected):
        failures.append(f"verificationStatus:{verification_status}!={expected}")
        return False
    return True


def evaluate_verification_for_idempotency(verification_status: str, case: dict[str, Any], failures: list[str]) -> bool:
    if str(case.get("expectedVerificationStatus") or "") != "IDEMPOTENT_HIT":
        return True
    ok = verification_status == "IDEMPOTENT_HIT"
    if not ok:
        failures.append("idempotencyMiss")
    return ok


def evaluate_result(data_obj: dict[str, Any], case: dict[str, Any], failures: list[str]) -> bool:
    expected = case.get("expectedResult") if isinstance(case.get("expectedResult"), dict) else {}
    if not expected:
        return True
    result = data_obj.get("result") if isinstance(data_obj.get("result"), dict) else {}
    ok = True
    for key, value in expected.items():
        actual = result.get(key)
        if actual != value:
            failures.append(f"result.{key}:{actual}!={value}")
            ok = False
    return ok


def evaluate_event_status(base_url: str, case: dict[str, Any], data_obj: dict[str, Any], variables: dict[str, Any], timeout_sec: float, tokens: dict[str, str], failures: list[str]) -> bool:
    expected = case.get("expectedEventStatus")
    if expected is None:
        return True
    event_id = data_obj.get("eventId") or variables.get("eventToResolve")
    if not event_id:
        failures.append("missingEventIdForStatusCheck")
        return False
    event = get_event(base_url, int(event_id), timeout_sec, token_for("admin", tokens))
    status = str(event.get("status") or "")
    if status != str(expected):
        failures.append(f"eventStatus:{status}!={expected}")
        return False
    return True


def evaluate_persistence(base_url: str, case: dict[str, Any], data_obj: dict[str, Any], timeout_sec: float, tokens: dict[str, str], failures: list[str]) -> bool:
    if not bool(case.get("requirePersistedAction", False)):
        return True
    event_id = as_int(data_obj.get("eventId"))
    action_code = str(data_obj.get("actionCode") or "")
    idempotency_key = str(data_obj.get("idempotencyKey") or "")
    if not event_id:
        failures.append("missingEventIdForPersistence")
        return False
    event = get_event(base_url, event_id, timeout_sec, token_for("admin", tokens))
    actions = event.get("actions") if isinstance(event.get("actions"), list) else []
    for action in actions:
        if not isinstance(action, dict):
            continue
        if str(action.get("actionType") or "") != action_code:
            continue
        metadata = parse_json_obj(action.get("metadataJson"))
        if metadata.get("idempotencyKey") == idempotency_key and metadata.get("runbook") is True:
            expected_status = case.get("expectedActionStatus")
            if expected_status is not None and str(action.get("status") or "") != str(expected_status):
                failures.append(f"persistedActionStatus:{action.get('status')}!={expected_status}")
                return False
            if "expectedActionExecuted" in case:
                expected_executed = bool(case.get("expectedActionExecuted"))
                actual_executed = action.get("executed")
                if actual_executed is not expected_executed:
                    failures.append(f"persistedActionExecuted:{actual_executed}!={expected_executed}")
                    return False
            return True
    failures.append("persistedRunbookActionNotFound")
    return False


def evaluate_latency(case: dict[str, Any], latency_ms: float, failures: list[str]) -> bool:
    max_latency = float(case.get("maxLatencyMs") or 0)
    if max_latency <= 0:
        return True
    ok = latency_ms <= max_latency
    if not ok:
        failures.append(f"latencyExceeded:{latency_ms:.1f}>{max_latency:.1f}")
    return ok


def get_event(base_url: str, event_id: int, timeout_sec: float, token: str) -> dict[str, Any]:
    resp, _ = request_json("GET", base_url.rstrip() + f"/api/ai/aiops/events/{event_id}", None, timeout_sec, token)
    if int(resp.get("code") or 0) != 200:
        return {}
    return resp.get("data") if isinstance(resp.get("data"), dict) else {}


def parse_json_obj(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str) and value.strip():
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, dict) else {}
        except Exception:
            return {}
    return {}


def as_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str) and value.strip():
        try:
            return int(value.strip())
        except ValueError:
            return None
    return None


def as_str_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(x) for x in value if x is not None]


def empty_result(case_id: str, failures: list[str]) -> RunbookCaseResult:
    return RunbookCaseResult(
        case_id=case_id,
        ok=False,
        app_code=0,
        status="",
        executed=None,
        verification_status="",
        denied_ok=False,
        catalog_ok=False,
        dry_run_ok=False,
        execution_ok=False,
        idempotency_ok=False,
        verification_ok=False,
        persistence_ok=False,
        event_status_ok=False,
        latency_ok=False,
        latency_ms=0.0,
        event_id=None,
        action_id=None,
        detail_failures=failures,
        error=";".join(failures),
    )


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, math.ceil((pct / 100) * len(ordered)) - 1))
    return ordered[idx]


def rate(results: list[RunbookCaseResult], attr: str) -> float:
    if not results:
        return 0.0
    return sum(1 for r in results if bool(getattr(r, attr))) / len(results)


def write_report(results: list[RunbookCaseResult], output: Path, run_id: str, doc_id: str) -> None:
    latencies = [r.latency_ms for r in results if r.latency_ms > 0]
    lines: list[str] = []
    lines.append("# AIOps Runbook Evaluation Report")
    lines.append("")
    lines.append(f"- Generated at: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- Run ID: {run_id}")
    lines.append(f"- Doc ID: {doc_id}")
    lines.append(f"- Cases: {len(results)}")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Success rate: {sum(1 for r in results if r.ok) / len(results):.2%}" if results else "- Success rate: 0.00%")
    lines.append(f"- Catalog pass rate: {rate(results, 'catalog_ok'):.2%}")
    lines.append(f"- Permission deny pass rate: {rate(results, 'denied_ok'):.2%}")
    lines.append(f"- Dry-run pass rate: {rate(results, 'dry_run_ok'):.2%}")
    lines.append(f"- Execution pass rate: {rate(results, 'execution_ok'):.2%}")
    lines.append(f"- Idempotency pass rate: {rate(results, 'idempotency_ok'):.2%}")
    lines.append(f"- Verification pass rate: {rate(results, 'verification_ok'):.2%}")
    lines.append(f"- Persistence pass rate: {rate(results, 'persistence_ok'):.2%}")
    lines.append(f"- Event status pass rate: {rate(results, 'event_status_ok'):.2%}")
    lines.append(f"- Latency budget pass rate: {rate(results, 'latency_ok'):.2%}")
    lines.append(f"- Errors: {sum(1 for r in results if r.error)}")
    lines.append(f"- P50 latency ms: {statistics.median(latencies) if latencies else 0:.1f}")
    lines.append(f"- P95 latency ms: {percentile(latencies, 95):.1f}")
    lines.append("")
    lines.append("## Failed Or Risky Cases")
    lines.append("")
    lines.append("| Case | OK | App | Status | Executed | Verify | Latency | Details |")
    lines.append("|---|---:|---:|---|---:|---|---:|---|")
    for r in results:
        if not r.ok:
            lines.append(f"| {r.case_id} | {str(r.ok).lower()} | {r.app_code} | {r.status} | {r.executed} | {r.verification_status} | {r.latency_ms:.1f} | `{','.join(r.detail_failures)}` |")
    lines.append("")
    lines.append("## Per Case")
    lines.append("")
    lines.append("| Case | OK | App | Status | Executed | Verify | Event | Action | Latency ms |")
    lines.append("|---|---:|---:|---|---:|---|---:|---:|---:|")
    for r in results:
        lines.append(
            f"| {r.case_id} | {str(r.ok).lower()} | {r.app_code} | {r.status} | {r.executed} | "
            f"{r.verification_status} | {r.event_id or ''} | {r.action_id or ''} | {r.latency_ms:.1f} |"
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/aiops_runbook_eval_dataset.json")
    parser.add_argument("--output", default="")
    parser.add_argument("--timeout-sec", type=float, default=20.0)
    parser.add_argument("--doc-id", default="50")
    parser.add_argument("--admin-bearer-token", default="")
    parser.add_argument("--internal-bearer-token", default="")
    parser.add_argument("--user-bearer-token", default="")
    add_auth_args(parser)
    args = parser.parse_args()

    dataset_path = Path(args.dataset)
    cases = load_json(dataset_path)
    run_id = datetime.now().strftime("%Y%m%d%H%M%S")
    admin_token = strip_bearer_prefix(args.admin_bearer_token or resolve_bearer_token(args, args.base_url, args.timeout_sec))
    tokens = {
        "admin": admin_token,
        "internal": strip_bearer_prefix(args.internal_bearer_token or ""),
        "user": strip_bearer_prefix(args.user_bearer_token or ""),
    }
    variables: dict[str, Any] = {"runId": run_id, "docId": args.doc_id}
    results = [evaluate_case(args.base_url, case, variables, args.timeout_sec, tokens) for case in cases]
    output = Path(args.output) if args.output else Path(f"aiops_runbook_eval_report_{datetime.now().strftime('%Y%m%d-%H%M%S')}.md")
    write_report(results, output, run_id, args.doc_id)
    for result in results:
        status = "OK" if result.ok else "RISK"
        print(
            f"[{status}] {result.case_id} app={result.app_code} status={result.status} "
            f"executed={result.executed} verify={result.verification_status} "
            f"latency={result.latency_ms:.1f} details={result.detail_failures}"
        )
    print(f"written to {output}")
    return 0 if all(r.ok for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
