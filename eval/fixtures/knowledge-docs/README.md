# 知识入库测试文件

本目录同时供 Postman 手工测试与 `tools/ingestion_chunk_eval.py` 自动化评测使用。

文件类型：

- `ing-md-*.md`：中文 Markdown、多级标题和规则正文。
- `ing-pdf-*.pdf`：可提取中文文本的有效 PDF。
- `ing-docx-*.docx`：中文 Word 文档。
- `ing-long-001.md`：超长文本拆分与 overlap 测试。
- `ing-short-001.md`：同章节短片段合并测试。
- `ing-idempotent-001.md`：与 `ing-md-001.md` 内容相同，用于幂等测试。
- `ing-invalid-001.pdf`：扩展名为 PDF 的纯文本非法文件，预期拒绝。
- `ing-empty-001.md`：空白文档，预期拒绝。
- `manifest.json`：文件、SHA-256、大小和预期结果清单。

## Postman

请求：

```text
POST http://localhost:8080/api/ai/knowledge/docs/upload
Authorization: Bearer <INTERNAL_TOKEN>
Content-Type: multipart/form-data
```

`Body -> form-data`：

| Key | 类型 | 示例 |
| --- | --- | --- |
| `file` | File | 选择本目录中的文件 |
| `title` | Text | 手机无票发布规则 |
| `sourceType` | Text | GUIDE |
| `sourceTrustLevel` | Text | HIGH |
| `docVersion` | Text | fixture-v1 |
| `async` | Text | false |

有效文件预期返回文档、任务与 ACTIVE 索引版本；非法 PDF 和空文档预期返回业务错误，不能生成有效知识文档。

## 重新生成

```powershell
python tools/generate_knowledge_fixtures.py
```

自动化脚本默认优先读取本目录：

```powershell
python tools/ingestion_chunk_eval.py `
  --base-url http://localhost:8080 `
  --dataset eval/datasets/ingestion_chunk_eval_dataset.json `
  --fixture-dir eval/fixtures/knowledge-docs `
  --bearer-token $INTERNAL_TOKEN
```
