# Spec 最终验收运行手册

更新时间：2026-07-27

## 1. 审计结论

按 `docs/specs/README.md` 和各 Spec 的验收标准统计：

- 8 组核心功能已经实现，其中受控 Repair、AIOps 修复闭环和 Durable Runtime 已形成当前版本的完整在线 PASS 证据。
- 3 组人工/交付验收尚未完成。
- 合计需要执行 11 个验收批次，覆盖 64 个不重复验收点。
- 2026-07-27 已完成 21 个验收点：`APPROVAL-AC-001~005`、`SECURITY-01`、`AIOPS-AC-001~005`、`RUNTIME-AC-001~005`、`PUB-AC-001~005`；其余 43 个仍需当前版本证据或人工签字。
- `final-acceptance-traceability.md` 已覆盖当前 64 个验收点，最终状态以该表和报告文件共同为准。

| 批次 | 功能 | 验收点数 | 当前证据 |
| --- | --- | ---: | --- |
| 1 | 企业级 RAG 入库切片与 Query Rewrite | 14 | 实现完成，在线报告缺失 |
| 2 | 智能排查 DIRECT/REACT/DAG 路由 | 5 | 专用评测脚本和数据集缺失 |
| 3 | Repair/Runbook 受控审批 | 6 | DONE：12/12 受控操作评测 + AIOps 失败转人工/幂等评测 |
| 4 | AIOps 修复、验证、转人工与 MTTR | 5 | DONE：16/16，闭环通过率 100%，P95=124.4ms |
| 5 | Durable Agent Runtime | 5 | DONE：15/15，恢复成功率 100%，重复副作用数 0 |
| 6 | 发布助手采纳、风险回流与业务判断 | 5 | DONE：反馈闭环 20/20；目标业务评测 12/12，事实保真和硬风险召回 100%，安全误杀 0% |
| 7 | 知识缺口治理 | 5 | DONE：26/26，治理链路通过率100%，P95=5348.5ms |
| 8 | Trace 与 Eval Dashboard 正规化 | 5 | 实现完成，需要跨链路复核 |
| 9 | 前端角色、状态和 Runtime 生命周期 | 7 | 构建曾通过，人工验收缺失 |
| 10 | 五分钟演示与透明降级 | 2 | 清单存在，未完整走查 |
| 11 | GitHub 交付 | 5 | 根 README、check-env、smoke-test 尚未完成 |

说明：历史报告只能说明历史代码状态。最终签字必须使用当前 commit、当前数据集和当前环境重新生成报告。

## 2. 当前明确缺口

以下项目无法仅靠现有脚本完整证明：

1. Spec 要求的 `tools/agent_routing_eval.py` 和 `eval/datasets/agent_routing_eval_dataset.json` 尚不存在，智能排查路由先按本手册进行人工验收。
2. `ingestion_chunk_eval.py` 尚未自动覆盖 LLM 语义切片故障降级和新版本入库失败保留旧 ACTIVE 版本，需要故障注入人工验收。
3. `query_rewrite_eval.py` 尚未自动覆盖 Rewrite 模型故障和非知识工具参数隔离，需要人工验收。
4. 前端没有完整 E2E/视觉回归脚本，需要三角色人工验收。
5. GitHub Spec 要求的 `scripts/check-env.ps1`、`scripts/smoke-test.ps1` 和根 `README.md` 当前不存在，因此 GH-AC-001 至 GH-AC-004 现在不能签字。
6. 企业 RAG 的文件上传和 Rewrite 调试前端尚未开发；本轮通过 Postman和 Python 脚本验收，不阻塞后端 Spec，但不能宣称前端已支持。

## 3. 验收前准备

### 3.1 依赖

确认以下服务可访问：

- Spring Boot：`8080`
- MySQL
- PostgreSQL
- Redis
- RabbitMQ
- Elasticsearch
- Milvus、etcd、MinIO
- Prometheus
- LLM 与 Embedding 服务

创建报告目录：

```powershell
cd F:\Online_used_marketplace
New-Item -ItemType Directory -Force eval\reports | Out-Null
```

设置临时变量，不要把 Token 写入仓库：

```powershell
$BASE_URL = "http://localhost:8080"
$USER_TOKEN = "<USER_JWT>"
$OTHER_USER_TOKEN = "<OTHER_USER_JWT>"
$INTERNAL_TOKEN = "<INTERNAL_JWT>"
$ADMIN_TOKEN = "<ADMIN_JWT>"
$DOC_ID = 19
```

测试环境启动后端前设置：

```powershell
$env:AI_AGENT_RUNTIME_FAULT_INJECTION_ENABLED = "true"
$env:KNOWLEDGE_QUERY_REWRITE_SHADOW_MODE = "false"
$env:KNOWLEDGE_SEMANTIC_CHUNK_ENABLED = "true"
```

健康检查：

```powershell
Invoke-RestMethod "$BASE_URL/actuator/health"
```

### 3.2 构建基线

```powershell
.\mvnw.cmd clean test
```

```powershell
cd used_marketplace_frontend
npm install
npm run build
cd ..
```

通过标准：后端测试和前端生产构建均为成功；跳过的集成测试必须注明原因，不能当作已通过。

## 4. 批次 1：企业级 RAG 入库切片

运行实体 MD/PDF/DOCX 入库评测：

```powershell
python tools/ingestion_chunk_eval.py `
  --base-url $BASE_URL `
  --dataset eval/datasets/ingestion_chunk_eval_dataset.json `
  --fixture-dir eval/fixtures/knowledge-docs `
  --bearer-token $INTERNAL_TOKEN `
  --report eval/reports/ingestion_chunk_eval_report.md
```

再检查三存储：

```powershell
python tools/storage_consistency_eval.py `
  --base-url $BASE_URL `
  --bearer-token $INTERNAL_TOKEN `
  --sample-docs 20 `
  --output eval/reports/storage_consistency_after_ingestion_report.md
```

通过标准：

- MD/PDF/DOCX 有效文件正文非空，非法 PDF 和空文档被拒绝。
- Gold 正文覆盖率 100%，非 overlap 异常重复率为 0。
- 普通正文 chunk 不超过 600 Token。
- heading/page/source metadata 满足对应格式要求。
- 新 ACTIVE 版本的 DB/ES/Milvus chunkId 完全一致。

### 4.1 语义切片故障降级

仅在测试环境执行：

1. 将 `KNOWLEDGE_SEMANTIC_CHUNK_TIMEOUT_MS` 临时设为 `1` 并重启后端。
2. 用 Postman 上传 `eval/fixtures/knowledge-docs/ing-md-005.md`，`async=false`。
3. 后端日志应出现 `semantic split fallback`，接口仍成功完成规则切片和入库。
4. 查询 `/api/ai/knowledge/debug/chunks/by-doc/{docId}`，确认 chunk 非空、Token 未超限、正文未丢失。
5. 恢复正常超时配置并重启。

### 4.2 旧 ACTIVE 版本保留

该测试会暂停存储服务，只能在测试环境执行：

1. 记录一个已有文档的 `activeIndexVersion`、chunkId 和检索结果。
2. 暂停 ES 或 Milvus。
3. 为该文档创建强制 reindex 受控操作，ADMIN 审批并执行。
4. 预期操作失败或转人工，新版本不能切换为 ACTIVE。
5. 恢复存储服务后再次查询文档和检索结果。
6. 原 `activeIndexVersion`、原 chunk 和召回结果必须仍然存在。

### 4.3 新版本保留期与受控回滚

运行新增版本生命周期评测：

```powershell
python tools/knowledge_version_lifecycle_eval.py `
  --base-url $BASE_URL `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN `
  --out eval/reports/knowledge_version_lifecycle_eval_report.md
```

通过标准：

1. 新 revision 完成验证后，旧 revision 为 `RETIRED`，不再出现在检索结果。
2. 旧 revision 的 DB/ES/Milvus chunk 在 `retentionUntil` 前仍存在，默认保留 7 天。
3. 回滚只能由 INTERNAL 创建 `ROLLBACK_KNOWLEDGE_REVISION` 计划、ADMIN 审批并执行。
4. 回滚重建三存储并通过一致性校验后，目标 revision 切回 `ACTIVE`，原 ACTIVE 变为 `RETIRED`。
5. 定时清理由 `KnowledgeRetiredVersionCleanupServiceImplTest` 覆盖；生产环境不应通过缩短保留期来验收。

## 5. 批次 1B：Query Rewrite

自动化主流程状态：**DONE（2026-08-01）**。

证据：`eval/reports/query_rewrite_eval_report.md`，12/12 通过；Recall@5
`83.33% -> 100.00%`，MRR `0.653 -> 0.743`，实体/否定保留率均为 100%，
fallback=0、shadow=0、P95=7615.9ms。该证据不替代本节末尾的故障降级和工具隔离人工检查。

```powershell
python tools/query_rewrite_eval.py `
  --base-url $BASE_URL `
  --dataset eval/datasets/query_rewrite_eval_dataset.json `
  --top-k 5 `
  --bearer-token $INTERNAL_TOKEN `
  --report eval/reports/query_rewrite_eval_report.md
```

通过标准：

- 简单问题走 fast path。
- 子查询不超过 3 条，并覆盖数据集中的多意图。
- 实体、型号、金额和否定条件保留率 100%。
- Rewrite Recall@5 不低于 baseline。
- MRR 相比 baseline 下降不超过 0.01。
- 低置信度/无召回子集 Gold 命中数必须提高。
- Rewrite 新增 P95 不超过配置预算，超时回退原始 Query。

故障降级人工检查：

1. 临时设置 `KNOWLEDGE_QUERY_REWRITE_TIMEOUT_MS=1` 并重启。
2. 调用 `/api/ai/knowledge/search`，请求体设置 `"rewriteEnabled": true`。
3. 响应应包含 `fallback=true/fallbackReason`，同时原始 Query 仍完成检索。
4. 调用 MySQL、Prometheus 或日志工具，确认工具参数没有经过 Query Rewrite。

## 6. 批次 2：智能排查路由

当前没有专用自动化脚本，使用 Postman 调用：

```text
POST /api/ai/agent/investigate
Authorization: Bearer <INTERNAL_TOKEN>
Content-Type: application/json
```

DIRECT：

```json
{
  "sessionId": "accept-direct",
  "question": "查询当前接口P95延迟",
  "maxSteps": 4
}
```

预期：`strategy=DIRECT`，只调用目标工具一次。

REACT：

```json
{
  "sessionId": "accept-react",
  "question": "先查询P95指标，再根据结果定位原因",
  "maxSteps": 5
}
```

预期：`strategy=REACT`，后续步骤引用前一步 observation。

DAG：

```json
{
  "sessionId": "accept-dag",
  "question": "同时查询P95指标、错误日志和知识库超时规则并汇总结论",
  "maxSteps": 6
}
```

预期：`strategy=DAG`，存在至少两个无虚假依赖的并行只读节点。

权限与 Trace：

1. 用 USER Token 调用同一接口，预期 403 且没有工具执行记录。
2. 保存成功响应中的 `traceId`。
3. INTERNAL 调用 `GET /api/ai/agent/traces/{traceId}`。
4. 确认路由策略、步骤、工具 observation、耗时和状态可还原。

## 7. 批次 3：受控 Repair/Runbook

```powershell
python tools/controlled_operation_eval.py `
  --base-url $BASE_URL `
  --doc-id $DOC_ID `
  --user-bearer-token $USER_TOKEN `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN `
  --output eval/reports/controlled_operation_eval_report.md
```

通过标准：

- 未审批不能执行。
- INTERNAL 不能审批，USER 不能创建。
- 执行只能使用审批时的参数快照。
- Repair 完成后三存储验证通过才可关闭。
- 旧 Repair/Runbook 真执行入口返回 409。
- HIGH 风险批量操作只能转人工。

## 8. 批次 4：AIOps 修复闭环

验收结果（2026-07-27）：`eval/reports/aiops_remediation_loop_eval_report.md`，16/16 通过，闭环通过率 100%，P95=124.4ms。

```powershell
python tools/aiops_remediation_loop_eval.py `
  --base-url $BASE_URL `
  --doc-id $DOC_ID `
  --user-bearer-token $USER_TOKEN `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN `
  --output eval/reports/aiops_remediation_loop_eval_report.md
```

通过标准：

- 正常链路完成诊断、审批、执行、验证和 RESOLVED。
- 验证失败进入 ESCALATED，`manualRequired=true`。
- 同一幂等键只产生一次副作用。
- INTERNAL/USER 不能审批和真实执行。
- MTTR 平均值、P50、P95 与离线计算误差不超过 1ms。

## 9. 批次 5：Durable Agent Runtime

验收结果（2026-07-27）：`eval/reports/durable_runtime_eval_report.md`，15/15 通过，恢复成功率 100%，重复副作用数 0；P95 case latency=10068.0ms，性能优化另行跟踪。

确保启动后端前已设置：

```powershell
$env:AI_AGENT_RUNTIME_FAULT_INJECTION_ENABLED = "true"
```

运行：

```powershell
python tools/durable_runtime_eval.py `
  --base-url $BASE_URL `
  --user-bearer-token $USER_TOKEN `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN `
  --output eval/reports/durable_runtime_eval_report.md
```

通过标准：

- 故障恢复跳过已成功工具节点，attemptCount 不增加。
- 并发恢复只有一个 lease 获胜。
- USER 无权访问；INTERNAL 无权执行生命周期写操作。
- 快照重放不重新调用工具。
- 实时只读重放至少调用一个只读工具。
- Run、Node、Checkpoint、traceId 可关联。

## 10. 批次 6：发布助手反馈闭环

验收结果（2026-07-27）：`eval/reports/publish_feedback_eval_report.md`，20/20 通过，P95=597.2ms。`PUB-001` 耗时 9338.4ms，且当前模型额度不可用，发布助手使用静态降级文案；该报告只证明建议快照、采纳反馈、用户隔离、风险回流和指标链路。

在线模型业务验收补充（2026-08-01）：`eval/reports/publish_business_eval_report.md`，12/12 通过；事实保真率和硬风险阻断召回率均为 `100%`，安全样例误杀率 `0%`，规则去重率 `100%`，P95=`18972.7ms`。

需要两个不同的 USER Token：

```powershell
python tools/publish_feedback_eval.py `
  --base-url $BASE_URL `
  --user-bearer-token $USER_TOKEN `
  --other-user-bearer-token $OTHER_USER_TOKEN `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN `
  --output eval/reports/publish_feedback_eval_report.md
```

通过标准：

- assistId、traceId、输入、输出和证据可追踪。
- PARTIAL 反馈只记录实际采纳字段。
- 其他用户不能查询或修改该 assistId。
- ADMIN 风险结果能回流。
- 采纳率和风险指标与固定样本离线计算一致。

## 11. 批次 7：知识缺口治理

当前状态：**DONE（2026-08-01）**。证据：`eval/reports/knowledge_gap_eval_report.md`，
26/26 通过，`GAP-AC-001~005` 均有对应在线用例。

旧报告 `knowledge_gap_eval_report_20260630-134414.md` 只有 21/26 通过，不能签字。重新运行：

```powershell
python tools/knowledge_gap_eval.py `
  --base-url $BASE_URL `
  --doc-id $DOC_ID `
  --user-bearer-token $USER_TOKEN `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN `
  --output eval/reports/knowledge_gap_eval_report.md
```

通过标准：

- 无召回拒答创建 OPEN 缺口。
- 同义重复问题聚合并增加 occurrenceCount。
- 缺知识与缺字段正确区分。
- 关联已入库文档后回归成功才 RESOLVED。
- 回归失败保持未关闭并记录 evalRunId。
- 26 条必须全部通过。

## 12. 批次 8：Trace 与 Eval Dashboard

完成批次 2 至 7 后执行：

```powershell
Invoke-RestMethod `
  -Uri "$BASE_URL/api/ai/agent/eval-dashboard?recentLimit=50" `
  -Headers @{ Authorization = "Bearer $INTERNAL_TOKEN" }
```

逐项确认：

1. DIRECT、REACT、DAG、失败 Agent 的 Trace 均可查询。
2. 失败 Run 的 `status/success/errorMsg` 与实际异常一致。
3. 每份评测报告对应一条 `ai_eval_run`，包含 total、passed、P95。
4. 发布反馈、知识缺口和 AIOps 业务记录可反查 traceId/evalRunId。
5. USER 查询 Trace 和 Dashboard 返回 403。
6. observation/metadata 不包含 Token、手机号、邮箱和完整隐式思维链。

## 13. 批次 9：前端人工验收

角色矩阵：

| 检查项 | USER | INTERNAL | ADMIN |
| --- | --- | --- | --- |
| 商城与 RAG | 可用 | 可用 | 可用 |
| 智能排查 | 禁止 | 可用 | 可用 |
| Runtime 查看 | 禁止 | 可用 | 可用 |
| Runtime 暂停/恢复/取消/重放 | 禁止 | 禁止 | 可用 |
| Repair 计划 | 禁止 | 可创建/查看 | 可审批执行 |

执行步骤：

1. 三个角色分别登录，检查导航项。
2. 手输 `/ai/investigate`、`/ai/runtime` 和 Runtime 详情 URL，确认前端守卫与后端权限一致。
3. 展示 DIRECT、REACT、DAG，页面不能只输出整段 JSON。
4. 创建 Repair 计划后，审批参数只读，不能把 docId 19 改成 20。
5. 检查 PENDING_APPROVAL、VERIFYING、RESOLVED、ESCALATED 对应按钮状态。
6. 检查 Runtime 的暂停、恢复、取消、快照重放和实时只读重放按钮。
7. 每个新页面检查 loading、empty、403、409、500 和成功状态。
8. 在 375x812、768x1024、1440x900 下检查无文字溢出和控件重叠。

## 14. 批次 10：五分钟演示

严格按 `docs/FIVE_MINUTE_DEMO_CHECKLIST.md` 完整执行一次：

1. USER 展示可信 RAG 回答与引用。
2. INTERNAL 展示知识缺口沉淀和回归。
3. INTERNAL 展示智能排查和 Runtime。
4. INTERNAL 创建 Repair 计划，ADMIN 审批执行。
5. 展示验证、Trace、MTTR 和 Eval Dashboard。
6. 人为关闭一个非核心依赖，确认页面明确展示降级，不能把降级结果标记为正式通过。

记录演示日期、操作者、关键 traceId、operationId 和 runId。

## 15. 批次 11：GitHub 交付

当前只能先执行构建验收：

```powershell
.\mvnw.cmd -DskipTests package
```

```powershell
cd used_marketplace_frontend
npm run build
cd ..
```

其余验收需先补齐根 README、`scripts/check-env.ps1`、`scripts/smoke-test.ps1` 和幂等 seed：

- 新环境只看 README 能理解目标、闭环、权限和指标来源。
- 缺 Redis/Milvus 时 check-env 明确指出服务和地址，不打印密码。
- 重复 seed 和 smoke-test 不产生重复主数据。
- secret scan 不包含 Key、JWT secret、真实密码、私网配置和个人绝对路径。

在这些文件完成之前，不得将 GitHub Spec 标记为 DONE。

## 16. 签字规则

每个批次完成后：

1. 报告保存到 `eval/reports/`。
2. 报告必须记录 commit SHA、执行时间、环境和数据集路径。
3. 所有 case 通过后，才把对应 AC 更新为 `DONE`。
4. 人工项记录截图、traceId、runId 或 operationId。
5. 任一失败应修复代码或不合理 Spec，不能仅修改问题文本规避失败。
6. 最后更新 `docs/specs/final-acceptance-traceability.md`，填入报告路径和日期。
