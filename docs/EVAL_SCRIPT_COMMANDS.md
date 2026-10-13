# 评测脚本使用指令

本文档整理项目当前主要评测脚本的 PowerShell 执行方式。建议在项目根目录执行：

```powershell
cd F:\Online_used_marketplace
New-Item -ItemType Directory -Force eval\reports | Out-Null
```

## 1. Token 变量

不要把真实 JWT 长期提交到 GitHub。临时测试时在 PowerShell 会话中设置变量即可：

```powershell
$BASE_URL = "http://localhost:8080"

$USER_TOKEN = "<USER_JWT>"

$INTERNAL_TOKEN = "<INTERNAL_JWT>"

$ADMIN_TOKEN = "<ADMIN_JWT>"

$AIOPS_WEBHOOK_TOKEN = "<AIOPS_WEBHOOK_SECRET>"

# publish_feedback_eval.py 的越权用例需要另一个普通用户 token。
$OTHER_USER_TOKEN = "替换为另一个 USER 角色 JWT"
```

也可以用环境变量供部分脚本自动读取：

```powershell
$env:EVAL_BEARER_TOKEN = $INTERNAL_TOKEN
```

## 2. RAG / Retrieval

普通检索评测：

```powershell
python tools/retrieval_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --dataset eval/datasets/retrieval_eval_dataset.json --seed-docs eval/datasets/retrieval_eval_seed_docs.json --top-k 5 --out eval/reports/retrieval_eval_report.md
```

Hard case 检索评测：

```powershell
python tools/retrieval_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --dataset eval/datasets/retrieval_eval_hard_dataset.json --seed-docs eval/datasets/retrieval_eval_seed_docs.json --top-k 5 --out eval/reports/retrieval_eval_hard_report.md
```

RAG Chat 生成评测：

```powershell
python tools/rag_chat_eval.py --base-url $BASE_URL --bearer-token $USER_TOKEN --dataset eval/datasets/retrieval_eval_hard_dataset.json --top-k 5 --out eval/reports/rag_chat_eval_report.md
```

## 3. Agent / ReAct / Graph

Knowledge Agent 编排评测：

```powershell
python tools/agent_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --dataset eval/datasets/agent_eval_dataset.json --out eval/reports/agent_eval_report.md
```

ReAct Agent 评测：

```powershell
python tools/react_agent_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --dataset eval/datasets/react_agent_eval_dataset.json --out eval/reports/react_agent_eval_report.md
```

DeepEval Agent 通用指标评测：

```powershell
python -m venv .venv-deepeval
.\.venv-deepeval\Scripts\python.exe -m pip install -r requirements-eval.txt
$env:DEEPEVAL_TELEMETRY_OPT_OUT = "1"
.\.venv-deepeval\Scripts\python.exe tools/deepeval_agent_eval.py --base-url $BASE_URL --internal-bearer-token $INTERNAL_TOKEN --user-bearer-token $USER_TOKEN --admin-bearer-token $ADMIN_TOKEN
```

默认模式由 DeepEval 计算工具正确性、参数正确性、调用顺序和最小权限指标，不调用 Judge 模型，也不消耗模型 Token。脚本同时保留路由策略、HTTP 权限、Trace 完整性、Observation、DAG 依赖和步数预算等项目级确定性断言。

只验证 DeepEval 安装与适配层时执行：

```powershell
.\.venv-deepeval\Scripts\python.exe tools/deepeval_agent_eval.py --self-test
```

需要额外启用 LLM-as-Judge 任务完成度时，使用独立 Judge 模型凭证：

```powershell
$env:DEEPEVAL_JUDGE_MODEL = "替换为兼容模型名"
$env:DEEPEVAL_JUDGE_API_KEY = "替换为Judge模型Key"
$env:DEEPEVAL_JUDGE_BASE_URL = "替换为OpenAI兼容地址"
.\.venv-deepeval\Scripts\python.exe tools/deepeval_agent_eval.py --base-url $BASE_URL --internal-bearer-token $INTERNAL_TOKEN --user-bearer-token $USER_TOKEN --enable-llm-judge
```

Java Agent 的 Trace 目前由项目自身持久化，默认评测不会把事后转换的数据伪装成 DeepEval 原生运行时 Trace。因此 DeepEval 的 `TaskCompletionMetric`、`PlanAdherenceMetric` 等原生 Trace 指标暂不作为 CI 门禁；待后续通过 OpenTelemetry/OpenInference 桥接真实 Span 后再启用。

## 4. AIOps

AIOps 工具链评测：

```powershell
python tools/aiops_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --dataset eval/datasets/aiops_eval_dataset.json --out eval/reports/aiops_eval_report.md
```

AIOps Runbook 评测：

```powershell
python tools/aiops_runbook_eval.py --base-url $BASE_URL --doc-id 19 --user-bearer-token $USER_TOKEN --internal-bearer-token $INTERNAL_TOKEN --admin-bearer-token $ADMIN_TOKEN --output eval/reports/aiops_runbook_eval_report.md
```

AIOps 修复闭环评测：

```powershell
python tools/aiops_remediation_loop_eval.py --base-url $BASE_URL --doc-id 19 --user-bearer-token $USER_TOKEN --internal-bearer-token $INTERNAL_TOKEN --admin-bearer-token $ADMIN_TOKEN --output eval/reports/aiops_remediation_loop_eval_report.md
```

AIOps 持续发现与多 Agent 受控修复评测（启动后端前需显式启用测试开关）：

```powershell
$env:AIOPS_ALERTMANAGER_ENABLED = "true"
$env:AIOPS_ALERT_WEBHOOK_TOKEN = $AIOPS_WEBHOOK_TOKEN
$env:AIOPS_DEMO_ENABLED = "true"

python tools/aiops_continuous_detection_eval.py --base-url $BASE_URL --webhook-token $AIOPS_WEBHOOK_TOKEN --user-bearer-token $USER_TOKEN --internal-bearer-token $INTERNAL_TOKEN --admin-bearer-token $ADMIN_TOKEN --dataset eval/datasets/aiops_continuous_detection_eval_dataset.json --output eval/reports/aiops_continuous_detection_eval_report.md
```

当前数据集为 27 条状态化 E2E 用例，覆盖 webhook 验签、幂等/聚合、异步诊断、证据追踪、Prompt Injection 隔离、三级权限、审批执行、独立验证、监控侧恢复通知与 MTTD/MTTR。

隔离 Docker Runbook 默认关闭时的权限/参数边界评测：

```powershell
python tools/docker_runbook_eval.py --base-url $BASE_URL --user-bearer-token $USER_TOKEN --internal-bearer-token $INTERNAL_TOKEN --admin-bearer-token $ADMIN_TOKEN
```

完成 `deploy/runbook/README.md` 的 VM helper 和 SSH 公钥配置、并在启动后端前显式启用后，执行真实容器重启与健康检查：

```powershell
$env:AI_AIOPS_DOCKER_RUNBOOK_ENABLED = "true"
$env:AI_AIOPS_DOCKER_RUNBOOK_TRANSPORT = "ssh-helper"
$env:AI_AIOPS_DOCKER_RUNBOOK_SSH_HOST = "192.168.152.128"
$env:AI_AIOPS_DOCKER_RUNBOOK_SSH_USER = "gshhh"
$env:AI_AIOPS_DOCKER_RUNBOOK_SSH_KEY_PATH = "$HOME\.ssh\id_ed25519"
$env:AI_AIOPS_DOCKER_RUNBOOK_HEALTH_URL = "http://192.168.152.128:18080/"

python tools/docker_runbook_eval.py --base-url $BASE_URL --user-bearer-token $USER_TOKEN --internal-bearer-token $INTERNAL_TOKEN --admin-bearer-token $ADMIN_TOKEN --expect-enabled --live-execute
```

脚本只传逻辑 `targetId=marketplace-demo-web`。容器名、回滚镜像、端口和 helper 路径来自受信任配置，不能由 API 覆盖。

## 5. Tool Execution Harness

```powershell
python tools/harness_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --dataset eval/datasets/harness_eval_dataset.json --output eval/reports/harness_eval_report.md
```

## 6. 知识缺口治理

```powershell
python tools/knowledge_gap_eval.py --base-url $BASE_URL --doc-id 19 --user-bearer-token $USER_TOKEN --internal-bearer-token $INTERNAL_TOKEN --admin-bearer-token $ADMIN_TOKEN --output eval/reports/knowledge_gap_eval_report.md
```

## 7. 发布助手反馈闭环

完整评测需要两个 USER token：

```powershell
python tools/publish_feedback_eval.py --base-url $BASE_URL --user-bearer-token $USER_TOKEN --other-user-bearer-token $OTHER_USER_TOKEN --internal-bearer-token $INTERNAL_TOKEN --admin-bearer-token $ADMIN_TOKEN --output eval/reports/publish_feedback_eval_report.md
```

如果暂时没有 `$OTHER_USER_TOKEN`，可以先跑下面这条，但越权相关 case 可能失败：

```powershell
python tools/publish_feedback_eval.py --base-url $BASE_URL --user-bearer-token $USER_TOKEN --internal-bearer-token $INTERNAL_TOKEN --admin-bearer-token $ADMIN_TOKEN --output eval/reports/publish_feedback_eval_report.md
```

## 8. RabbitMQ 知识入库

单文档异步入库评测：

```powershell
python tools/rabbit_ingestion_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --dataset eval/datasets/rabbit_ingestion_eval_dataset.json --doc-id 19 --report-dir eval/reports
```

多文档异步入库评测：

```powershell
python tools/rabbit_ingestion_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --dataset eval/datasets/rabbit_ingestion_eval_dataset.json --doc-id 19 --doc-id 20 --doc-id 21 --report-dir eval/reports
```

## 9. 三存储一致性 / Repair

检查单个文档：

```powershell
python tools/storage_consistency_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --doc-id 19 --output eval/reports/storage_consistency_eval_report.md
```

抽样检查：

```powershell
python tools/storage_consistency_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --sample-docs 20 --output eval/reports/storage_consistency_sample_report.md
```

强制修复单个文档：

```powershell
python tools/storage_repair.py --base-url $BASE_URL --bearer-token $ADMIN_TOKEN --doc-id 19 --force-reindex --output eval/reports/storage_repair_report.md
```

抽样强制修复：

```powershell
python tools/storage_repair.py --base-url $BASE_URL --bearer-token $ADMIN_TOKEN --sample-docs 20 --force-reindex --output eval/reports/storage_repair_sample_report.md
```

只查看将要修复哪些文档，不真正执行：

```powershell
python tools/storage_repair.py --base-url $BASE_URL --bearer-token $ADMIN_TOKEN --sample-docs 20 --dry-run --output eval/reports/storage_repair_dry_run_report.md
```

## 10. 记忆系统

五层记忆评测：

```powershell
python tools/memory_priority_eval.py --base-url $BASE_URL --bearer-token $INTERNAL_TOKEN --dataset eval/datasets/memory_priority_eval_dataset.json --out eval/reports/memory_priority_eval_report.md
```

L2 语义记忆 smoke test：

```powershell
python tools/l2_memory_smoke_test.py --base $BASE_URL --token $INTERNAL_TOKEN
```

L1 HTTP 回归：

```powershell
$env:EVAL_BEARER_TOKEN = $USER_TOKEN
python tools/l1_memory_regression.py
```

L1 WebSocket 回归：

```powershell
$env:EVAL_BEARER_TOKEN = $USER_TOKEN
python tools/l1_memory_regression_ws.py
```

## 11. 个人材料归档

简历生成、简历指标摘要及面试学习材料已移至本机独立备份目录，未删除。它们不属于项目运行或业务评测依赖，不再提供仓库内执行命令。

## 12. 推荐回归顺序

```text
1. retrieval_eval.py
2. rag_chat_eval.py
3. react_agent_eval.py
4. agent_eval.py
5. harness_eval.py
6. storage_consistency_eval.py
7. storage_repair.py
8. rabbit_ingestion_eval.py
9. aiops_runbook_eval.py
10. aiops_remediation_loop_eval.py
11. knowledge_gap_eval.py
12. publish_feedback_eval.py
13. memory_priority_eval.py
14. ingestion_chunk_eval.py
15. query_rewrite_eval.py
16. token_governance_eval.py
```

## 14. 企业级入库切片与 Query Rewrite

运行 Query Rewrite A/B 前应在启动后端时设置 `$env:KNOWLEDGE_QUERY_REWRITE_SHADOW_MODE="false"`；生产首轮默认保持 Shadow Mode，只记录改写计划而不改变召回结果。

```powershell
python tools/ingestion_chunk_eval.py --base-url http://localhost:8080 --dataset eval/datasets/ingestion_chunk_eval_dataset.json --fixture-dir eval/fixtures/knowledge-docs --bearer-token $INTERNAL_TOKEN
```

```powershell
python tools/query_rewrite_eval.py --base-url http://localhost:8080 --dataset eval/datasets/query_rewrite_eval_dataset.json --bearer-token $INTERNAL_TOKEN
```

前者优先读取 `eval/fixtures/knowledge-docs/` 中可供 Postman 复用的实体 MD/PDF/DOCX 文件并同步入库；文件缺失时才动态生成。它会校验真实 Token 上限、章节/页码 metadata、activeIndexVersion 和幂等拒绝。后者对同一问题分别发送 `rewriteEnabled=false/true`，比较 Recall@5、MRR、nDCG@5、关键字段保留率和 P95。

修改入库数据集后可重新生成实体 fixture：

```powershell
python tools/generate_knowledge_fixtures.py
```

## 15. Token 治理 MVP

该评测只调用预算预览接口，不调用大模型，也不会消耗模型 Token：

```powershell
python tools/token_governance_eval.py --base-url http://localhost:8080 --dataset eval/datasets/token_governance_eval_dataset.json --internal-bearer-token $INTERNAL_TOKEN
```

模型可用时，再调用一次 RAG Chat，并使用其 `traceId` 查询真实或估算 Usage：

```powershell
Invoke-RestMethod -Headers @{Authorization="Bearer $INTERNAL_TOKEN"} "http://localhost:8080/api/ai/token-governance/usages?traceId=替换为traceId&limit=20"
```

查看最近 100 次聚合指标：

```powershell
Invoke-RestMethod -Headers @{Authorization="Bearer $INTERNAL_TOKEN"} "http://localhost:8080/api/ai/token-governance/metrics?recentLimit=100"
```

## 16. RAGAS RAG 评测

RAGAS 使用独立的 64 位 Python 环境，避免污染现有 DeepEval 环境。Windows 32 位 Python 无法安装 SciPy/Scikit-Network 依赖。

```powershell
C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -m venv .venv-ragas64
.\.venv-ragas64\Scripts\python.exe -m pip install -r requirements-ragas.txt
```

先运行不消耗 judge Token 的自测、数据集校验和分层确定性评测：

```powershell
.\.venv-ragas64\Scripts\python.exe tools\ragas_rag_eval.py --self-test
.\.venv-ragas64\Scripts\python.exe tools\ragas_dataset_validate.py

.\.venv-ragas64\Scripts\python.exe tools\ragas_rag_eval.py `
  --base-url http://localhost:8080 `
  --bearer-token $USER_TOKEN `
  --dataset eval/datasets/ragas_rag_smoke_dataset.json `
  --catalog eval/datasets/ragas_knowledge_catalog.json `
  --out eval/reports/ragas_rag_smoke_report.md

.\.venv-ragas64\Scripts\python.exe tools\ragas_rag_eval.py `
  --base-url http://localhost:8080 `
  --bearer-token $USER_TOKEN `
  --dataset eval/datasets/ragas_rag_development_v2_dataset.json `
  --catalog eval/datasets/ragas_knowledge_catalog.json `
  --out eval/reports/ragas_rag_development_v2_report.md `
  --concurrency 2

.\.venv-ragas64\Scripts\python.exe tools\ragas_rag_eval.py `
  --base-url http://localhost:8080 `
  --bearer-token $USER_TOKEN `
  --dataset eval/datasets/ragas_rag_regression_dataset.json `
  --catalog eval/datasets/ragas_knowledge_catalog.json `
  --out eval/reports/ragas_rag_regression_report.md `
  --concurrency 2

# 仅在 Regression 门禁通过后运行；不输出保留集逐例细节。
.\.venv-ragas64\Scripts\python.exe tools\ragas_rag_eval.py `
  --base-url http://localhost:8080 `
  --bearer-token $USER_TOKEN `
  --dataset eval/datasets/ragas_rag_holdout_dataset.json `
  --catalog eval/datasets/ragas_knowledge_catalog.json `
  --out eval/reports/ragas_rag_holdout_report.md `
  --concurrency 2 `
  --summary-only

# v2 只允许在 Development v2 与 Regression 都通过后盲测一次。
.\.venv-ragas64\Scripts\python.exe tools\ragas_rag_eval.py `
  --base-url http://localhost:8080 `
  --bearer-token $USER_TOKEN `
  --dataset eval/datasets/ragas_rag_holdout_v2_dataset.json `
  --catalog eval/datasets/ragas_knowledge_catalog.json `
  --out eval/reports/ragas_rag_holdout_v2_report.md `
  --concurrency 2 `
  --summary-only
```

默认指标为 `IDBasedContextPrecision/Recall` 和 `NonLLMContextPrecision/Recall`，并校验分组指标、Bootstrap 95% CI、Retrieval/E2E P95、技术错误以及只读评测模式覆盖率。Gold 使用逻辑知识 ID，避免 reindex 后物理 `docId` 变化污染结果。它们评估文档对齐和检索上下文，不评估自然语言答案是否忠实。

固定执行顺序为 `Smoke/Development -> Regression -> 新 Holdout`。Holdout 首次失败后应只依据聚合失败类型构造下一版开发集，并重新构造未见 Holdout；不要查看逐例输出后反复调参直到通过。

有独立 judge 凭证时再启用生成质量指标：

```powershell
$env:RAGAS_JUDGE_API_KEY = '<JUDGE_API_KEY>'
$env:RAGAS_JUDGE_BASE_URL = 'https://api.openai.com/v1'
$env:RAGAS_JUDGE_MODEL = 'gpt-4o-mini'
$env:RAGAS_JUDGE_EMBEDDING_MODEL = 'text-embedding-3-small'

.\.venv-ragas64\Scripts\python.exe tools\ragas_rag_eval.py `
  --base-url http://localhost:8080 `
  --bearer-token $USER_TOKEN `
  --enable-llm-judge
```

启用后增加 `Faithfulness`、LLM Context Precision/Recall 和 Answer Relevancy。judge 模型应与被测 RAG Chat 模型分离；报告必须记录 judge 模型和数据集版本。RAGAS 不替代现有 `retrieval_eval.py` 的 Recall@K、MRR、nDCG 等确定性排序指标。

## 13. 常见错误

### PowerShell 不能使用 CMD 的 `^`

错误写法：

```powershell
python tools/knowledge_gap_eval.py ^
  --base-url http://localhost:8080 ^
```

PowerShell 会把下一行的 `--base-url` 当成新表达式，报：

```text
一元运算符“--”后面缺少表达式
```

PowerShell 多行要用反引号：

```powershell
python tools/knowledge_gap_eval.py `
  --base-url $BASE_URL `
  --doc-id 19 `
  --user-bearer-token $USER_TOKEN `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN
```

更推荐直接使用本文档中的单行命令。

### 401

通常是 token 过期、token 没传，或登录用户不存在。

### 403

通常是角色不对。例如：

- 需要 INTERNAL，却传了 USER。
- 需要 ADMIN，却传了 INTERNAL。

### 连接失败

先确认后端启动：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

再确认依赖服务是否启动：MySQL、Redis、RabbitMQ、Elasticsearch、Milvus、PostgreSQL、Prometheus。

## 17. 知识生命周期验收

首次上线先观察 legacy 迁移量：

```powershell
python tools/knowledge_lifecycle_eval.py --base-url http://localhost:8080 --internal-bearer-token $INTERNAL_TOKEN
```

全部旧文档完成受控 reindex 后执行严格验收：

```powershell
python tools/knowledge_lifecycle_eval.py --base-url http://localhost:8080 --internal-bearer-token $INTERNAL_TOKEN --strict-no-legacy
```

详细迁移与回滚步骤见 `docs/RAG_RETRIEVAL_PRECISION_OPTIMIZATION.md`。

新版发布、保留期过滤与受控回滚端到端验收：

```powershell
python tools/knowledge_version_lifecycle_eval.py `
  --base-url $BASE_URL `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN `
  --out eval/reports/knowledge_version_lifecycle_eval_report.md
```

通过标准：四条用例全部通过，依次验证文档 `DRAFT`、索引任务/分块 `STAGING`、验证后 `ACTIVE`；旧版本应为 `RETIRED` 且不可检索，7 天保留期内索引块仍存在；回滚只能由 ADMIN 审批执行，并在版本切换后再次通过一致性验证。过期物理清理由后端定时任务处理，测试环境不要把保留期设为 0 天。
