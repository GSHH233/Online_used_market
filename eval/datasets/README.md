# Eval Datasets

This directory is the reconstructed dataset home for the final RAG + Agent project.

The original root-level dataset files were removed during cleanup, so these files are a new, normalized v2 baseline. They are not byte-identical recovery files. They are organized by project capability and match the current `tools/*.py` schemas.

## Dataset Map

- `retrieval_eval_dataset.json`: retrieval mode comparison for `vector-only`, `keyword-only`, and `hybrid`.
- `retrieval_eval_hard_dataset.json`: hard RAG Chat answer evaluation cases.
- `retrieval_eval_seed_docs.json`: seed knowledge docs corresponding to retrieval and RAG Chat cases.
- `agent_eval_dataset.json`: publish-assist Knowledge Agent orchestration cases.
- `react_agent_eval_dataset.json`: ReAct / Graph tool selection and permission cases.
- `hybrid_planner_eval_dataset.json`: hybrid Planner, bounded Replan, permission stability, and DAG validity cases.
- `memory_priority_eval_dataset.json`: L1/L2/L3 memory and memory graph evaluation cases.
- `harness_eval_dataset.json`: Tool Execution Harness retry, timeout, fallback, snapshot, and permission cases.
- `aiops_eval_dataset.json`: AIOps Agent, Prometheus, Graph, and permission cases.
- `aiops_runbook_eval_dataset.json`: AIOps low-risk runbook auto-repair cases.
- `aiops_remediation_loop_eval_dataset.json`: AIOps approval, controlled execution, escalation, idempotency, and MTTR cases.
- `durable_runtime_eval_dataset.json`: Runtime persistence, permission, checkpoint, recovery, pause/cancel, and replay cases.
- `controlled_operation_eval_dataset.json`: Approval-gated Repair/Runbook execution, role isolation, snapshot immutability, and legacy endpoint closure.
- `publish_feedback_eval_dataset.json`: publish-assist snapshot, adoption, product linking, risk outcome, permission, and metrics cases.
- `knowledge_gap_eval_dataset.json`: RAG refusal capture, dedup, PII redaction, governance, regression, recurrence, and metrics cases.
- `rabbit_ingestion_eval_dataset.json`: RabbitMQ ingestion async job cases for local doc ids `19-50`.
- `ingestion_chunk_eval_dataset.json`: Markdown/PDF/DOCX ingestion, token limit, metadata, rejection, and idempotency cases.
- `../fixtures/knowledge-docs/`: matching physical files for Postman and automated ingestion evaluation.
- `query_rewrite_eval_dataset.json`: fixed Query Rewrite A/B cases with Gold docs, protected terms, and negations.

## Common Commands

```powershell
python tools/retrieval_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/rag_chat_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/agent_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/react_agent_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/hybrid_planner_eval.py --base-url http://localhost:8080 --bearer-token <INTERNAL_JWT>
python tools/memory_priority_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/harness_eval.py --base-url http://localhost:8080 --bearer-token <INTERNAL_JWT>
python tools/aiops_eval.py --base-url http://localhost:8080 --bearer-token <INTERNAL_JWT>
python tools/aiops_runbook_eval.py --base-url http://localhost:8080 --admin-bearer-token <ADMIN_JWT> --internal-bearer-token <INTERNAL_JWT> --user-bearer-token <USER_JWT> --doc-id 19
python tools/aiops_remediation_loop_eval.py --base-url http://localhost:8080 --admin-bearer-token <ADMIN_JWT> --internal-bearer-token <INTERNAL_JWT> --user-bearer-token <USER_JWT> --doc-id 19
python tools/publish_feedback_eval.py --base-url http://localhost:8080 --admin-bearer-token <ADMIN_JWT> --internal-bearer-token <INTERNAL_JWT> --user-bearer-token <USER_JWT> --other-user-bearer-token <OTHER_USER_JWT>
python tools/knowledge_gap_eval.py --base-url http://localhost:8080 --admin-bearer-token <ADMIN_JWT> --internal-bearer-token <INTERNAL_JWT> --user-bearer-token <USER_JWT> --doc-id 19
python tools/rabbit_ingestion_eval.py --base-url http://localhost:8080 --bearer-token <INTERNAL_JWT>
python tools/ingestion_chunk_eval.py --base-url http://localhost:8080 --fixture-dir eval/fixtures/knowledge-docs --bearer-token <INTERNAL_JWT>
python tools/query_rewrite_eval.py --base-url http://localhost:8080 --bearer-token <INTERNAL_JWT>
```

## Notes

- Retrieval and RAG Chat cases use `goldDocIds`. If your local DB doc ids differ, update `expectedDocId` in `retrieval_eval_seed_docs.json` and run retrieval with `--seed-docs eval/datasets/retrieval_eval_seed_docs.json --use-seed-doc-ids`.
- Rabbit ingestion cases currently cover local knowledge document ids `19-50`; update this range if your local DB changes.
- Runbook execution cases use `${docId}` and `${runId}` templates. Pass `--doc-id <existingDocId>` when needed.
- Regenerate the physical ingestion fixtures with `python tools/generate_knowledge_fixtures.py` after changing `ingestion_chunk_eval_dataset.json`.
# Token 治理

- `token_governance_eval_dataset.json`：验证场景预算、低优先级上下文裁剪、问题/证据保留与输出预算配置。
- 对应脚本：`tools/token_governance_eval.py`。该评测只调用预算预览接口，不消耗模型 Token。
