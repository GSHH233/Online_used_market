# Ingestion and Chunk Eval Report

- generatedAt: `2026-08-01T02:35:32`
- result: `PASS`
- cases: `20/20`
- latency P95: `2323.5ms`

| Case | Format | App code | Doc | Task | Chunks | Max tokens | Version | Result | Detail |
| --- | --- | ---: | ---: | --- | ---: | ---: | --- | --- | --- |
| ING-MD-001 | md | 200 | 69 | SUCCESS | 2 | 33 | True | OK |  |
| ING-MD-002 | md | 200 | 70 | SUCCESS | 2 | 36 | True | OK |  |
| ING-MD-003 | md | 200 | 71 | SUCCESS | 2 | 26 | True | OK |  |
| ING-MD-004 | md | 200 | 72 | SUCCESS | 1 | 47 | True | OK |  |
| ING-MD-005 | md | 200 | 73 | SUCCESS | 1 | 44 | True | OK |  |
| ING-PDF-001 | pdf | 200 | 74 | SUCCESS | 1 | 53 | True | OK |  |
| ING-PDF-002 | pdf | 200 | 75 | SUCCESS | 1 | 59 | True | OK |  |
| ING-PDF-003 | pdf | 200 | 76 | SUCCESS | 1 | 49 | True | OK |  |
| ING-PDF-004 | pdf | 200 | 77 | SUCCESS | 1 | 50 | True | OK |  |
| ING-PDF-005 | pdf | 200 | 78 | SUCCESS | 1 | 51 | True | OK |  |
| ING-DOCX-001 | docx | 200 | 79 | SUCCESS | 1 | 45 | True | OK |  |
| ING-DOCX-002 | docx | 200 | 80 | SUCCESS | 1 | 36 | True | OK |  |
| ING-DOCX-003 | docx | 200 | 81 | SUCCESS | 1 | 33 | True | OK |  |
| ING-DOCX-004 | docx | 200 | 82 | SUCCESS | 1 | 39 | True | OK |  |
| ING-DOCX-005 | docx | 200 | 83 | SUCCESS | 1 | 42 | True | OK |  |
| ING-LONG-001 | md | 200 | 84 | SUCCESS | 23 | 598 | True | OK |  |
| ING-SHORT-001 | md | 200 | 85 | SUCCESS | 1 | 37 | True | OK |  |
| ING-IDEMPOTENT-001 | duplicate | 200 | - | - | - | - | - | OK | warnings=['IDEMPOTENT_HIT'] |
| ING-INVALID-001 | invalid-pdf | 400 | - | - | - | - | - | OK | rejected appCode=400, message=文件扩展名与真实MIME不一致: text/plain |
| ING-EMPTY-001 | md | 400 | - | - | - | - | - | OK | rejected appCode=400, message=文档未提取到有效文本 |
