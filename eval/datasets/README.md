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
- `deepeval_agent_eval_dataset.json`: unified DIRECT/ReAct/DAG cases consumed by DeepEval tool correctness and permission metrics.
- `ragas_knowledge_catalog.json`: stable logical knowledge catalog used to normalize duplicate and reindexed physical documents.
- `ragas_rag_smoke_dataset.json`: 10 fast online smoke cases.
- `ragas_rag_regression_dataset.json`: 50 visible release-gate regression cases.
- `ragas_rag_holdout_dataset.json`: 15 sealed generalization cases; never tune retrieval rules directly against this file.
- `ragas_rag_development_v2_dataset.json`: 24 visible development cases abstracted from Holdout v1 failure categories without copying its questions.
- `ragas_rag_holdout_v2_dataset.json`: 20 newly authored sealed cases used once after Development v2 and Regression passed; its first result remains frozen.
- `ragas_rag_development_v3_dataset.json`: 30 visible cases derived only from Holdout v2 aggregate failure categories, covering transaction normalization, multi-hop evidence planning, and refusal grounding.
- `ragas_rag_holdout_v3_dataset.json`: 25 sealed generalization cases; SHA-256 and the one-time result are recorded in `ragas_rag_holdout_v3_manifest.json`.
- `ragas_rag_development_v4_dataset.json`: 30 visible cases derived only from frozen Holdout v3 aggregate failure types; it covers transaction semantics, multi-hop evidence, refusal grounding, paraphrases, and noisy questions without copying sealed questions.
- `ragas_rag_holdout_v4_dataset.json`: 30 newly authored blind cases created only after the v4 implementation/Judge freeze; every case contains a complete `reference` answer and atomic `referenceFacts`.
- `ragas_rag_development_v5_dataset.json`: 32 visible cases derived only from the frozen Holdout v4 aggregate weak groups. It contains 8 cases each for condition disclosure, medium rewrites, multi-hop evidence, and paraphrases, with complete `reference` and `referenceFacts` fields and no copied sealed questions.
- `ragas_release_freeze_v4_manifest.json`: SHA-256 freeze of the RAG implementation, configuration, Judge protocol, Gold catalog, Development v4, regression data, and pre-Holdout reports.
- `ragas_rag_holdout_v4_manifest.json`: sealed Holdout v4 hash, one-run policy, frozen implementation hash, and immutable first-run result.
- `ragas_rag_eval_dataset.json`: legacy compact RAGAS dataset retained for compatibility.
- `memory_priority_eval_dataset.json`: L1/L2/L3 memory and memory graph evaluation cases.
- `memory_quality_development_dataset.json`: MEM-06 five-layer memory development set, 48 cases across identity, update, negation, duplicate, recovery, and long-history categories.
- `memory_quality_holdout_dataset.json`: MEM-06 independent holdout set, 24 cases. Run only after the development implementation is frozen.
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
.\.venv-deepeval\Scripts\python.exe tools/deepeval_agent_eval.py --base-url http://localhost:8080 --internal-bearer-token <INTERNAL_JWT> --user-bearer-token <USER_JWT>
.\.venv-ragas64\Scripts\python.exe tools/ragas_dataset_validate.py
.\.venv-ragas64\Scripts\python.exe tools/ragas_rag_eval.py --base-url http://localhost:8080 --bearer-token <JWT> --dataset eval/datasets/ragas_rag_smoke_dataset.json --catalog eval/datasets/ragas_knowledge_catalog.json --out eval/reports/ragas_rag_smoke_report.md
.\.venv-ragas64\Scripts\python.exe tools/ragas_rag_eval.py --base-url http://localhost:8080 --bearer-token <JWT> --dataset eval/datasets/ragas_rag_regression_dataset.json --catalog eval/datasets/ragas_knowledge_catalog.json --out eval/reports/ragas_rag_regression_report.md --concurrency 2
.\.venv-ragas64\Scripts\python.exe tools/ragas_rag_eval.py --base-url http://localhost:8080 --bearer-token <JWT> --dataset eval/datasets/ragas_rag_holdout_dataset.json --catalog eval/datasets/ragas_knowledge_catalog.json --out eval/reports/ragas_rag_holdout_report.md --concurrency 2 --summary-only
.\.venv-ragas64\Scripts\python.exe tools/ragas_rag_eval.py --base-url http://localhost:8080 --bearer-token <JWT> --dataset eval/datasets/ragas_rag_development_v2_dataset.json --catalog eval/datasets/ragas_knowledge_catalog.json --out eval/reports/ragas_rag_development_v2_report.md --concurrency 2
.\.venv-ragas64\Scripts\python.exe tools/ragas_rag_eval.py --base-url http://localhost:8080 --bearer-token <JWT> --dataset eval/datasets/ragas_rag_holdout_v2_dataset.json --catalog eval/datasets/ragas_knowledge_catalog.json --out eval/reports/ragas_rag_holdout_v2_report.md --concurrency 2 --summary-only
.\.venv-ragas64\Scripts\python.exe tools/ragas_rag_eval.py --base-url http://localhost:8080 --bearer-token <JWT> --dataset eval/datasets/ragas_rag_development_v3_dataset.json --catalog eval/datasets/ragas_knowledge_catalog.json --out eval/reports/ragas_rag_development_v3_report.md --concurrency 2
.\.venv-ragas64\Scripts\python.exe tools/ragas_rag_eval.py --base-url http://localhost:8080 --bearer-token <JWT> --dataset eval/datasets/ragas_rag_development_v4_dataset.json --catalog eval/datasets/ragas_knowledge_catalog.json --out eval/reports/ragas_rag_development_v4_report.md --concurrency 2
.\.venv-ragas64\Scripts\python.exe tools/ragas_rag_eval.py --base-url http://localhost:8080 --bearer-token <JWT> --dataset eval/datasets/ragas_rag_development_v5_dataset.json --catalog eval/datasets/ragas_knowledge_catalog.json --out eval/reports/ragas_rag_development_v5_report.md --concurrency 2
.\.venv-ragas64\Scripts\python.exe tools/ragas_release_freeze.py verify
python tools/memory_priority_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/memory_quality_eval.py --base-url http://localhost:8080 --dataset eval/datasets/memory_quality_development_dataset.json
# Freeze the implementation before the first holdout run:
python tools/memory_quality_eval.py --base-url http://localhost:8080 --dataset eval/datasets/memory_quality_holdout_dataset.json
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
- Regenerate the reviewed MEM-06 datasets with `python tools/generate_memory_quality_datasets.py`; review Gold labels and freeze hashes before a holdout run.
# Token 治理

- `token_governance_eval_dataset.json`：验证场景预算、低优先级上下文裁剪、问题/证据保留与输出预算配置。
- 对应脚本：`tools/token_governance_eval.py`。该评测只调用预算预览接口，不消耗模型 Token。

## RAG 精度与生命周期

- RAGAS Gold 使用稳定逻辑知识 ID，不直接依赖会因 reindex 变化的物理 `docId`。
- `tools/ragas_rag_eval.py` 默认门槛为 ID Context Recall >= 0.90、ID Context Precision >= 0.55、单例完整召回通过率 >= 0.90、Retrieval P95 <= 4s 且技术错误数为 0。
- 每轮执行顺序固定为 Smoke/Development -> Regression -> 新 Holdout；只有前两级通过后才执行新 Holdout，`--summary-only` 隐藏保留集逐例输出。Holdout 首次结果必须冻结，不能在同一版本上反复调参。
- `tools/knowledge_lifecycle_eval.py` 只读检查逻辑知识修订、ACTIVE 唯一性、内容哈希重复和 legacy 迁移进度。
- Development v4 最终冻结候选通过：Faithfulness `0.9167`、Answer Relevancy `0.8500`、LLM Context Precision `0.9061`、LLM Context Recall `0.8889`，E2E P95 `1.71s`；50 条 Regression 同步通过。
- Holdout v4 已按冻结协议仅盲测一次并封存为 `RISK`：生成质量与性能门禁通过，但 ID Context Recall `0.8722`、逐题完整召回通过率 `0.8000` 未达到 `0.90` 门槛。不得针对 Holdout v4 单题继续修改实现或重跑。
- Development v5 冻结候选通过：32/32 完整召回，ID Context Recall `1.0000`、ID Context Precision `0.6599`、E2E P95 `2.14s`；50 条 Regression 同步达到 50/50 完整召回、ID Context Recall `1.0000`、E2E P95 `1.48s`。本轮未启用独立 LLM Judge，这些指标只证明检索上下文与性能门禁通过。
- 下一步必须先冻结 v5 实现，再创建来源独立的 Holdout v5 并仅盲测一次；不得回看 Holdout v4 单题继续调参，也不能用 Development v5 成绩代替泛化结论。
