# 评测脚本使用指令

本文档整理项目当前主要评测脚本的 PowerShell 执行方式。建议在项目根目录执行：

```powershell
cd <project-root>
New-Item -ItemType Directory -Force eval\reports | Out-Null
```

## 1. Token 变量

不要把真实 JWT 长期提交到 GitHub。临时测试时在 PowerShell 会话中设置变量即可：

```powershell
$BASE_URL = "http://localhost:8080"

$USER_TOKEN = "<USER_JWT>"

$INTERNAL_TOKEN = "<INTERNAL_JWT>"

$ADMIN_TOKEN = "<ADMIN_JWT>"

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

## 11. 简历 / 指标摘要工具

这两个不是业务回归评测，按需执行：

```powershell
python tools/resume_metrics_summary.py
```

```powershell
python tools/generate_optimized_resume.py
```

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
