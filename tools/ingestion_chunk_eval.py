#!/usr/bin/env python3
"""Evaluate multi-format ingestion, chunk metadata, token limits and idempotency."""

from __future__ import annotations

import argparse
import io
import json
import math
import textwrap
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

from eval_auth import add_auth_args, resolve_bearer_token


def make_pdf(text: str) -> bytes:
    try:
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.cidfonts import UnicodeCIDFont
        from reportlab.pdfgen import canvas
        from reportlab.lib.pagesizes import A4
    except ImportError as exc:
        raise RuntimeError(
            "reportlab is required only when PDF fixtures must be regenerated; "
            "install it with: python -m pip install reportlab"
        ) from exc

    lines: list[str] = []
    for paragraph in text.splitlines() or [text]:
        if not paragraph.strip():
            lines.append("")
            continue
        lines.extend(textwrap.wrap(paragraph, width=34, replace_whitespace=False) or [""])

    pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))
    output = io.BytesIO()
    writer = canvas.Canvas(output, pagesize=A4, pageCompression=1, invariant=1)
    writer.setTitle("Knowledge ingestion fixture")
    writer.setFont("STSong-Light", 11)
    _, height = A4
    y = height - 48
    for line in lines:
        if y < 48:
            writer.showPage()
            writer.setFont("STSong-Light", 11)
            y = height - 48
        writer.drawString(48, y, line)
        y -= 16
    writer.save()
    return output.getvalue()


def make_docx(text: str) -> bytes:
    paragraphs = "".join(
        f"<w:p><w:r><w:t>{escape(part)}</w:t></w:r></w:p>"
        for part in text.splitlines() if part.strip()
    )
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        f"<w:body>{paragraphs}<w:sectPr/></w:body></w:document>"
    )
    content_types = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/word/document.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        "</Types>"
    )
    rels = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="word/document.xml"/></Relationships>'
    )
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, value in (
                ("[Content_Types].xml", content_types),
                ("_rels/.rels", rels),
                ("word/document.xml", document)):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, value)
    return buffer.getvalue()


def build_fixture(case: dict[str, Any], cache: dict[str, tuple[str, bytes]]) -> tuple[str, bytes]:
    if case["format"] == "duplicate":
        return cache[case["duplicateOf"]]
    content = str(case.get("content") or "")
    if case.get("contentRepeat"):
        content = str(case["contentRepeat"]) * int(case.get("repeat", 1))
    case_id = case["caseId"].lower()
    fmt = case["format"]
    if fmt == "pdf":
        result = (f"{case_id}.pdf", make_pdf(content))
    elif fmt == "invalid-pdf":
        result = (f"{case_id}.pdf", content.encode("utf-8"))
    elif fmt == "docx":
        result = (f"{case_id}.docx", make_docx(content))
    else:
        result = (f"{case_id}.md", content.encode("utf-8"))
    cache[case["caseId"]] = result
    return result


def fixture(case: dict[str, Any],
            cache: dict[str, tuple[str, bytes]],
            fixture_dir: Path) -> tuple[str, bytes]:
    if case["format"] == "duplicate":
        result = cache[case["duplicateOf"]]
        cache[case["caseId"]] = result
        return result
    case_id = case["caseId"].lower()
    extension = "pdf" if case["format"] in {"pdf", "invalid-pdf"} else (
        "docx" if case["format"] == "docx" else "md"
    )
    path = fixture_dir / f"{case_id}.{extension}"
    if path.is_file():
        result = (path.name, path.read_bytes())
        cache[case["caseId"]] = result
        return result
    return build_fixture(case, cache)


def multipart(fields: dict[str, str], filename: str, content: bytes) -> tuple[bytes, str]:
    boundary = "----codex-" + uuid.uuid4().hex
    parts: list[bytes] = []
    for name, value in fields.items():
        parts.extend([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
            str(value).encode("utf-8"),
            b"\r\n",
        ])
    parts.extend([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
        b"Content-Type: application/octet-stream\r\n\r\n",
        content,
        b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ])
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def request_json(request: urllib.request.Request, timeout: float) -> tuple[dict[str, Any], float]:
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    return body, (time.perf_counter() - started) * 1000


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"} if token else {}


def get_chunks(base_url: str, doc_id: int, token: str, timeout: float) -> list[dict[str, Any]]:
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/ai/knowledge/debug/chunks/by-doc/{doc_id}",
        headers=auth_headers(token),
    )
    body, _ = request_json(request, timeout)
    return body.get("data") or []


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0
    values = sorted(values)
    return values[max(0, min(len(values) - 1, math.ceil(len(values) * ratio) - 1))]


def response_code(response: dict[str, Any]) -> int:
    try:
        return int(response.get("code") or 0)
    except (TypeError, ValueError):
        return 0


def heading_values(metadata: list[dict[str, Any]]) -> list[str]:
    values: list[str] = []
    for item in metadata:
        heading_path = item.get("headingPath")
        if isinstance(heading_path, list):
            values.extend(str(value) for value in heading_path if str(value).strip())
        elif heading_path:
            values.append(str(heading_path))
    return values


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default="eval/datasets/ingestion_chunk_eval_dataset.json")
    parser.add_argument("--fixture-dir", default="eval/fixtures/knowledge-docs")
    parser.add_argument("--timeout-sec", type=float, default=60)
    parser.add_argument("--max-tokens", type=int, default=600)
    parser.add_argument("--report", default="")
    add_auth_args(parser)
    args = parser.parse_args()
    token = resolve_bearer_token(args, args.base_url, args.timeout_sec)
    cases = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    fixture_dir = Path(args.fixture_dir)
    endpoint = args.base_url.rstrip("/") + "/api/ai/knowledge/docs/upload"
    cache: dict[str, tuple[str, bytes]] = {}
    rows: list[dict[str, Any]] = []

    for case in cases:
        filename, content = fixture(case, cache, fixture_dir)
        fields = {
            "title": str(case.get("title") or case["caseId"]),
            "sourceType": "GUIDE",
            "sourceTrustLevel": "HIGH",
            "docVersion": "eval-v1",
            "async": "false",
        }
        body, content_type = multipart(fields, filename, content)
        request = urllib.request.Request(
            endpoint,
            data=body,
            headers={**auth_headers(token), "Content-Type": content_type},
            method="POST",
        )
        row: dict[str, Any] = {"caseId": case["caseId"], "format": case["format"]}
        try:
            response, latency = request_json(request, args.timeout_sec)
            app_code = response_code(response)
            app_message = str(response.get("message") or "")
            data = response.get("data") or {}
            warnings = data.get("warnings") or []
            if case.get("expectRejected"):
                rejected = 400 <= app_code < 500
                row.update(
                    passed=rejected,
                    latencyMs=latency,
                    appCode=app_code,
                    detail=(f"rejected appCode={app_code}, message={app_message}"
                            if rejected else f"unexpectedly accepted appCode={app_code}"),
                )
            elif app_code != 200:
                row.update(
                    passed=False,
                    latencyMs=latency,
                    appCode=app_code,
                    detail=f"appCode={app_code}, message={app_message}",
                )
            elif case["format"] == "duplicate":
                expected = case.get("expectedWarning")
                row.update(
                    passed=expected in warnings,
                    latencyMs=latency,
                    appCode=app_code,
                    detail=f"warnings={warnings}",
                )
            else:
                document = data.get("document") or {}
                task = data.get("task") or {}
                doc_id = int(document.get("id") or 0)
                if doc_id <= 0:
                    raise ValueError(f"missing document.id, response={response}")
                chunks = get_chunks(args.base_url, doc_id, token, args.timeout_sec)
                texts = "\n".join(str(chunk.get("content") or "") for chunk in chunks)
                token_counts = [int(chunk.get("tokenCount") or 0) for chunk in chunks]
                metadata = [chunk.get("metadata") or {} for chunk in chunks]
                active_version = document.get("activeIndexVersion")
                task_status = str(task.get("status") or (
                    "SUCCESS" if "IDEMPOTENT_HIT" in warnings and active_version else "UNKNOWN"
                ))
                version_aligned = bool(active_version) and all(
                    chunk.get("indexVersion") == active_version
                    and meta.get("indexVersion") == active_version
                    for chunk, meta in zip(chunks, metadata)
                )
                missing_terms = [term for term in case.get("expectedTerms", []) if term not in texts]
                headings = heading_values(metadata)
                missing_headings = [
                    heading for heading in case.get("expectedHeadings", [])
                    if not any(heading in actual for actual in headings)
                ]
                terms_ok = not missing_terms
                heading_ok = (
                    (not case.get("requireHeading") or bool(headings))
                    and not missing_headings
                )
                page_ok = not case.get("requirePage") or all(meta.get("pageFrom") is not None for meta in metadata)
                failures: list[str] = []
                if task_status != "SUCCESS":
                    failures.append(
                        f"task={task_status},stage={task.get('stage')},error={task.get('errorMsg')}"
                    )
                if not chunks:
                    failures.append("noChunks")
                if chunks and not all(token > 0 for token in token_counts):
                    failures.append("nonPositiveTokenCount")
                if max(token_counts, default=0) > args.max_tokens:
                    failures.append(f"tokenLimitExceeded:{max(token_counts)}>{args.max_tokens}")
                if missing_terms:
                    failures.append(f"missingTerms={missing_terms}")
                if not heading_ok:
                    failures.append(f"headingMismatch:actual={headings},missing={missing_headings}")
                if not page_ok:
                    failures.append("missingPageMetadata")
                if not version_aligned:
                    failures.append(
                        f"versionMismatch:active={active_version},"
                        f"chunks={[chunk.get('indexVersion') for chunk in chunks]}"
                    )
                passed = (
                    task_status == "SUCCESS"
                    and bool(chunks)
                    and all(token > 0 for token in token_counts)
                    and max(token_counts, default=0) <= args.max_tokens
                    and terms_ok
                    and heading_ok
                    and page_ok
                    and version_aligned
                )
                row.update(
                    passed=passed,
                    latencyMs=latency,
                    appCode=app_code,
                    docId=doc_id,
                    chunks=len(chunks),
                    maxTokens=max(token_counts, default=0),
                    taskStatus=task_status,
                    headingOk=heading_ok,
                    pageOk=page_ok,
                    termsOk=terms_ok,
                    versionAligned=version_aligned,
                    detail="; ".join(failures),
                )
        except urllib.error.HTTPError as exc:
            if case.get("expectRejected") and 400 <= exc.code < 500:
                row.update(passed=True, latencyMs=0, detail=f"rejected HTTP {exc.code}")
            else:
                row.update(passed=False, latencyMs=0, detail=f"HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')[:300]}")
        except Exception as exc:
            row.update(passed=False, latencyMs=0, detail=f"{type(exc).__name__}: {exc}")
        rows.append(row)
        print(f"[{'OK' if row['passed'] else 'RISK'}] {row['caseId']} "
              f"format={row['format']} detail={row.get('detail', '')}")

    passed_count = sum(bool(row["passed"]) for row in rows)
    latencies = [float(row.get("latencyMs") or 0) for row in rows if row.get("latencyMs")]
    passed = passed_count == len(rows)
    report_path = Path(args.report) if args.report else Path(
        f"ingestion_chunk_eval_report_{datetime.now():%Y%m%d-%H%M%S}.md"
    )
    lines = [
        "# Ingestion and Chunk Eval Report",
        "",
        f"- generatedAt: `{datetime.now().isoformat(timespec='seconds')}`",
        f"- result: `{'PASS' if passed else 'RISK'}`",
        f"- cases: `{passed_count}/{len(rows)}`",
        f"- latency P95: `{percentile(latencies, 0.95):.1f}ms`",
        "",
        "| Case | Format | App code | Doc | Task | Chunks | Max tokens | Version | Result | Detail |",
        "| --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |",
    ]
    for row in rows:
        lines.append(
            f"| {row['caseId']} | {row['format']} | {row.get('appCode', '-')} | "
            f"{row.get('docId', '-')} | {row.get('taskStatus', '-')} | "
            f"{row.get('chunks', '-')} | {row.get('maxTokens', '-')} | "
            f"{row.get('versionAligned', '-')} | {'OK' if row['passed'] else 'RISK'} | "
            f"{str(row.get('detail', '')).replace('|', '/')} |"
        )
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"written to {report_path}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
