# 未验收 Spec 与业务严格测试手册

更新时间：2026-08-01

本文只覆盖当前代码已经实现但缺少当前版本验收证据的能力，以及发布助手字段保真和风险判断的目标业务测试。已经有正式 PASS 报告的受控操作、AIOps 修复闭环、Durable Runtime 和发布反馈基础链路不要求重复执行。

## 1. 当前证据边界

| 能力 | 当前状态 | 本轮动作 |
| --- | --- | --- |
| 受控 Repair/Runbook | 已有 12/12 PASS | 不重测 |
| AIOps 修复、验证、转人工、MTTR | 已有 16/16 PASS | 不重测 |
| Durable Agent Runtime | 已有 15/15 PASS | 不重测 |
| 发布反馈闭环 | 已有 20/20 PASS | 仅证明闭环；当时走降级，不能证明 LLM 生成质量 |
| 企业级文档解析、切片和入库 | 缺当前报告 | 自动化测试 + 故障注入 |
| Query Rewrite | 12/12 在线 A/B 主流程 PASS | 故障注入与工具隔离另行验收 |
| 智能排查 DIRECT/REACT/DAG 路由 | 缺专用自动化报告 | 先人工验收，Hybrid Planner 另跑脚本 |
| Hybrid Planner 与有限 Replan | 8/8 在线 PASS，Replan 单元测试 PASS | 已完成 |
| 知识缺口治理 | 26/26 PASS，P95=5348.5ms | 已完成 |
| Token 治理 MVP | 8/8 预算预览 PASS，P95=205.3ms | 实际 Usage/RAG DONE/Prometheus 仍需链路检查 |
| Trace/Eval Dashboard | 实现完成，缺跨链路复核 | 人工验收 |
| 前端角色与状态页 | 缺在线签字 | 三角色人工验收 |
| 发布字段抽取 | 当前没有独立结构化抽取接口 | 本轮只测字段保真，不能宣称 Extraction F1 |
| 发布字段保真与风险判断 | 12/12 PASS，四项业务指标达标 | 已完成；P95=18972.7ms，性能单独观察 |
| GitHub 交付 | 根 README 和环境/冒烟脚本尚不完整 | 暂不签字 |

## 2. 测试前准备

### 2.1 PowerShell 变量

在项目根目录打开 PowerShell：

```powershell
cd <project-root>
New-Item -ItemType Directory -Force eval\reports | Out-Null

$BASE_URL = "http://localhost:8080"
$USER_TOKEN = "<USER_JWT>"
$OTHER_USER_TOKEN = "<ANOTHER_USER_JWT>"
$INTERNAL_TOKEN = "<INTERNAL_JWT>"
$ADMIN_TOKEN = "<ADMIN_JWT>"
$DOC_ID = 19
```

不要把真实 Token 写进脚本、YAML、Markdown 或 Git。JWT 过期出现 HTTP 401 时重新登录获取。

### 2.2 启动参数

以下环境变量必须在 IDEA 启动 Spring Boot **之前** 设置。若 IDEA 不是从当前 PowerShell 启动，需要把它们填入 Run/Debug Configuration 的 Environment variables：

```powershell
$env:AI_AGENT_RUNTIME_FAULT_INJECTION_ENABLED = "true"
$env:KNOWLEDGE_QUERY_REWRITE_SHADOW_MODE = "false"
$env:KNOWLEDGE_SEMANTIC_CHUNK_ENABLED = "true"
```

模型 Token 说明：

- 没有可用 Chat Model Token 时，仍可测规则切片、Rewrite fallback、权限、持久化和降级。
- 语义切片成功率、Rewrite 质量、发布文案质量必须在模型可用时才能签字。
- Embedding 服务也不可用时，不能验收向量召回和 DB/ES/Milvus 最终一致性。

### 2.3 健康与构建基线

```powershell
Invoke-RestMethod "$BASE_URL/actuator/health" | ConvertTo-Json -Depth 10
.\mvnw.cmd test
```

```powershell
cd <project-root>\used_marketplace_frontend
npm install
npm run build
cd <project-root>
```

判定：后端测试和前端构建必须成功；MySQL、PostgreSQL、Redis、RabbitMQ、ES、Milvus 相关健康项不能处于 DOWN。

## 3. 第一阶段：正常配置下的自动化测试

按下面顺序执行。文档入库会产生新文档和 chunk，因此先测入库，再测三存储一致性。

### 3.1 企业级文档解析、切片和入库

```powershell
python tools/ingestion_chunk_eval.py `
  --base-url $BASE_URL `
  --dataset eval/datasets/ingestion_chunk_eval_dataset.json `
  --fixture-dir eval/fixtures/knowledge-docs `
  --bearer-token $INTERNAL_TOKEN `
  --report eval/reports/ingestion_chunk_eval_report.md
```

检查：

- MD、PDF、DOCX、超长文档和短片段样例均有明确结果。
- 非法 PDF 和空文档必须被拒绝，不能生成 ACTIVE 空文档。
- 重复上传符合幂等约束。
- 报告必须写入 `eval/reports/ingestion_chunk_eval_report.md`。
- 若报告显示 `fallback=true`，只能证明降级可用，不能证明 LLM 语义切分有效。

### 3.2 入库后的 DB/ES/Milvus 一致性

```powershell
python tools/storage_consistency_eval.py `
  --base-url $BASE_URL `
  --sample-docs 20 `
  --bearer-token $INTERNAL_TOKEN `
  --output eval/reports/storage_consistency_after_ingestion_report.md
```

判定：抽样文档 `db/es/milvus` 数量相等，`missingEs=0`、`missingMilvus=0`，chunkId 集合一致。仅比较数量不算通过。

### 3.3 Query Rewrite

**状态：已完成（2026-08-01）**

验收证据：`eval/reports/query_rewrite_eval_report.md`。当前版本 12/12 通过；
Recall@5 从 83.33% 提升到 100.00%，MRR 从 0.653 提升到 0.743，
实体与否定条件保留率均为 100%，fallback 和 shadow mode 均为 0，P95 为 7615.9ms。
本结论覆盖 3.3 在线 A/B 主流程；模型故障降级和非知识工具隔离仍按 6.2 单独验收。

```powershell
python tools/query_rewrite_eval.py `
  --base-url $BASE_URL `
  --dataset eval/datasets/query_rewrite_eval_dataset.json `
  --top-k 5 `
  --bearer-token $INTERNAL_TOKEN `
  --report eval/reports/query_rewrite_eval_report.md
```

检查：主改写、子查询和同义改写保留型号、数字、否定词等关键事实；改写后的多路查询能进入检索；报告同时给出 Rewrite 成功和 fallback 情况。

### 3.4 Hybrid Planner 与有限动态 Replan

**状态：已完成（2026-08-01）**

验收证据：`eval/reports/hybrid_planner_eval_report.md`，在线接口 8/8 通过；
规则快路径、工具选择、USER 权限拒绝、React/Graph 步数预算和 DAG 合法性均通过。
线上数据未制造依赖故障，因此 Replan 追加节点、两轮上限、拒绝后不扩权和 LLM 失败回退
由 `HybridAgentPlannerTest`、`GraphReactAgentReplanTest` 提供确定性证据。

```powershell
python tools/hybrid_planner_eval.py `
  --base-url $BASE_URL `
  --dataset eval/datasets/hybrid_planner_eval_dataset.json `
  --bearer-token $INTERNAL_TOKEN `
  --out eval/reports/hybrid_planner_eval_report.md
```

判定：工具选择、权限拒绝、DAG 图、Replan 上限和 Trace 断言全部通过。数据集中的 `mode=USER` 是执行模式测试，不能替代下面的真实 USER JWT 接口权限测试。

### 3.5 知识缺口治理全量重测

**状态：已完成（2026-08-01）**

验收证据：`eval/reports/knowledge_gap_eval_report.md`，26/26 通过，治理链路通过率
100%，P95=5348.5ms。报告覆盖拒答沉淀、规范化去重、PII 脱敏、缺字段分类、
关联文档后的成功回归、失败回归不关闭、复发重开和治理指标。

```powershell
python tools/knowledge_gap_eval.py `
  --base-url $BASE_URL `
  --doc-id $DOC_ID `
  --user-bearer-token $USER_TOKEN `
  --internal-bearer-token $INTERNAL_TOKEN `
  --admin-bearer-token $ADMIN_TOKEN `
  --output eval/reports/knowledge_gap_eval_report.md
```

判定：本轮已达到 26/26；拒答沉淀、去重聚合、管理员治理、补文档后的回归和状态关闭均通过。后续变更仍须使用固定问题和断言回归，不能通过修改问题绕过失败。

### 3.6 Token 治理 MVP

**状态：预算装配自动化评测已完成（2026-08-01）**

验收证据：`eval/reports/token_governance_eval_report.md`，8/8 通过，P95=205.3ms，
累计裁剪 196353 Token。固定用例证明所有场景不超过输入预算，关键规则、当前问题、
RAG 证据和 Observation 得到保留，低优先级历史上下文按预期裁剪。
该报告不调用 LLM，因此真实 Usage 落库、RAG DONE Token 摘要和 Prometheus 指标仍需实际链路检查。

```powershell
python tools/token_governance_eval.py `
  --base-url $BASE_URL `
  --dataset eval/datasets/token_governance_eval_dataset.json `
  --internal-bearer-token $INTERNAL_TOKEN `
  --output eval/reports/token_governance_eval_report.md
```

判定：受保护的规则、用户问题和证据不能被裁剪；低优先级历史上下文在超预算时被裁剪；估算 Token 不超过场景预算。该脚本只测预算装配，不调用 LLM，因此不会消耗模型 Token。

### 3.7 发布字段保真与风险目标业务测试

验收结果（2026-08-01）：`eval/reports/publish_business_eval_report.md`，12/12 PASS。事实保真率 `100%`、硬风险阻断召回率 `100%`、安全样例误杀率 `0%`、规则去重率 `100%`，全部达到业务门槛；P95=`18972.7ms`，不阻塞本项功能签字，保留为后续性能优化基线。

```powershell
python tools/publish_business_eval.py `
  --base-url $BASE_URL `
  --dataset eval/datasets/publish_business_eval_dataset.json `
  --bearer-token $USER_TOKEN `
  --output eval/reports/publish_business_eval_report.md
```

该测试集是**目标标准**，不是适配当前代码的表征测试，覆盖：

- 型号、容量、电池、配件、维修、发票和来源等事实保真。
- `不接受微信转账`、`不是盗抢机`、引用平台禁令等否定或引用场景不误报。
- 预付款、微信转账、盗抢机、有锁机、绕过平台等硬风险必须阻断发布。
- 重复知识 chunk 不能重复扣分或重复生成同一规则证据。
- Prompt Injection 不得绕过风险规则。

当前实现若出现 FAIL，说明风险逻辑需要重构，不要降低断言或改问题来换取通过。建议最终门槛：事实保真率 `>= 95%`、硬风险阻断召回率 `100%`、安全样例误杀率 `<= 2%`、规则去重率 `100%`。

注意：当前接口接收的已经是结构化字段，本报告测的是“生成结果是否保留输入事实”，不是自然语言字段抽取 Precision/Recall/F1。要测真正字段抽取，需先新增 `rawText -> ProductFactExtractionVO`，并返回字段值、置信度和证据 span。

## 4. 第二阶段：智能排查统一入口人工验收

先准备请求头：

```powershell
$INTERNAL_HEADERS = @{ Authorization = "Bearer $INTERNAL_TOKEN" }
$USER_HEADERS = @{ Authorization = "Bearer $USER_TOKEN" }
```

### 4.1 DIRECT

```powershell
$body = @{
  sessionId = "accept-direct"
  question = "查询当前接口 P95 指标"
  maxSteps = 4
} | ConvertTo-Json

$direct = Invoke-RestMethod -Method Post `
  -Uri "$BASE_URL/api/ai/agent/investigate" `
  -Headers $INTERNAL_HEADERS `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
$direct | ConvertTo-Json -Depth 20
```

判定：`data.strategy=DIRECT`，只执行一个确定性只读工具，返回 `traceId` 和 routeReason。

### 4.2 REACT

```powershell
$body = @{
  sessionId = "accept-react"
  question = "先查询 P95 指标，再根据结果定位原因"
  maxSteps = 5
} | ConvertTo-Json

$react = Invoke-RestMethod -Method Post `
  -Uri "$BASE_URL/api/ai/agent/investigate" `
  -Headers $INTERNAL_HEADERS `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
$react | ConvertTo-Json -Depth 20
```

判定：`data.strategy=REACT`，后续步骤引用前一步 Observation，步数不超过 5。

### 4.3 DAG

```powershell
$body = @{
  sessionId = "accept-dag"
  question = "同时查询 P95 指标、错误日志和知识库超时规则并汇总结论"
  maxSteps = 6
} | ConvertTo-Json

$dag = Invoke-RestMethod -Method Post `
  -Uri "$BASE_URL/api/ai/agent/investigate" `
  -Headers $INTERNAL_HEADERS `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
$dag | ConvertTo-Json -Depth 20
```

判定：`data.strategy=DAG`，`graph` 中存在多个无虚假依赖的只读节点；节点 observation 汇总进入最终回答。

### 4.4 真实角色拒绝

```powershell
try {
  Invoke-RestMethod -Method Post `
    -Uri "$BASE_URL/api/ai/agent/investigate" `
    -Headers $USER_HEADERS `
    -ContentType "application/json; charset=utf-8" `
    -Body $body
  throw "USER token should have been denied"
} catch {
  $_.Exception.Response.StatusCode.value__
}
```

判定：USER 必须得到 HTTP 403；INTERNAL 和 ADMIN 可访问。前端隐藏菜单不是权限控制证据。

## 5. 第三阶段：Trace、Dashboard 与真实 Token 用量

使用上一步返回的 Trace：

```powershell
$TRACE_ID = $dag.data.traceId

Invoke-RestMethod `
  -Uri "$BASE_URL/api/ai/agent/traces/$TRACE_ID" `
  -Headers $INTERNAL_HEADERS | ConvertTo-Json -Depth 30

Invoke-RestMethod `
  -Uri "$BASE_URL/api/ai/agent/eval-dashboard?recentLimit=50" `
  -Headers $INTERNAL_HEADERS | ConvertTo-Json -Depth 30

Invoke-RestMethod `
  -Uri "$BASE_URL/api/ai/token-governance/usages?traceId=$TRACE_ID&limit=20" `
  -Headers $INTERNAL_HEADERS | ConvertTo-Json -Depth 20

Invoke-RestMethod `
  -Uri "$BASE_URL/api/ai/token-governance/metrics?recentLimit=100" `
  -Headers $INTERNAL_HEADERS | ConvertTo-Json -Depth 20
```

判定：

- Trace 包含 run、step、tool、observation、状态、耗时和错误信息，但不存储思维链、密钥或完整敏感 SQL。
- Dashboard 能看到成功与失败运行、评测指标和最近链路。
- Token usage 包含 prompt/completion/total、estimated/actual、trimmed 等字段，并能通过 traceId 关联。
- 使用 USER JWT 请求以上接口必须返回 403。
- `http://localhost:8080/actuator/prometheus` 中可搜索到 `ai_token_` 指标；若 404，先确认 actuator 暴露配置，而不是把 404 当作 Prometheus 服务故障。

## 6. 第四阶段：故障注入测试

这组测试放在正常测试最后执行，并且只在测试环境进行。

### 6.1 语义切片模型超时降级

1. 停止后端。
2. 在 IDEA Run Configuration 临时设置语义切片模型超时为极小值，例如 `KNOWLEDGE_SEMANTIC_CHUNK_TIMEOUT_MS=1`；具体键以当前 `application.yml` 映射为准。
3. 重启后端并重新执行 3.1。
4. 检查报告或日志：语义切片失败后回退规则切片，最终 chunk 非空，任务状态可追踪。

判定：降级成功不等于语义切片质量通过，两项证据分开记录。

### 6.2 Query Rewrite 模型失败降级

1. 停止后端。
2. 在 IDEA 中把 Rewrite 超时临时设为极小值，保持 `KNOWLEDGE_QUERY_REWRITE_SHADOW_MODE=false`。
3. 重启并重新执行 3.3。
4. 检查 `fallback=true`，原始 query 仍参与检索，接口不因 Rewrite 失败整体报 500。

### 6.3 新版本入库失败不破坏旧 ACTIVE 版本

1. 选一个已有 ACTIVE 文档并记录 docId、version 和 chunkId。
2. 在测试环境暂时停止 ES 或 Milvus。
3. 对同一来源提交新版本入库。
4. 恢复 ES/Milvus 后查询文档和三存储一致性。

判定：新任务明确 FAILED/DEGRADED；旧 ACTIVE 版本仍可检索，不能先删旧版本再发现新版本入库失败。

故障测试完成后恢复所有环境变量并重启服务，再跑一次 3.2。

## 7. 第五阶段：前端三角色人工验收

启动前端后，在桌面宽屏和移动端宽度各检查一次：

```powershell
cd <project-root>\used_marketplace_frontend
npm run dev
```

| 角色 | 必测页面 | 通过标准 |
| --- | --- | --- |
| USER | 商城、商品发布、AI 发布助手、RAG 对话 | 不显示知识治理、智能排查、Runtime、评测面板；发布结果不是原始 JSON |
| INTERNAL | 智能排查、知识库、知识缺口、Runtime、Eval Dashboard | 可诊断和 dry-run；不能审批或执行高风险写操作 |
| ADMIN | INTERNAL 全部页面、审批与 Repair/AIOps 执行 | 能审批、拒绝、执行、验证和查看转人工结果 |

补充检查：

- 手工输入受限 URL 时，前端路由守卫拦截；绕过前端直接请求时，后端仍返回 403。
- DIRECT/REACT/DAG 仅作为智能排查结果中的执行策略展示，不是三个独立业务菜单。
- FAILED、DEGRADED、WAITING_APPROVAL、RUNNING、COMPLETED 都有可读状态，不能只展示 JSON。
- Trace、风险证据和知识来源可展开查看，但普通用户看不到内部日志、SQL、Prometheus 查询和工具参数。

## 8. 最终签字规则

1. 每份报告记录当前 commit SHA、环境和生成时间；历史报告不能代替当前代码证据。
2. 正常回归脚本要求全量 PASS；知识缺口仍非全通过时保持 `IMPLEMENTED_PENDING_EVAL`。
3. 发布业务目标测试允许先失败，但失败必须形成规则重构清单，不能宣称风险逻辑达到严格业务标准。
4. LLM fallback 通过只说明可用性，不说明模型质量；在线模型验收需单独记录模型、Prompt 版本和 Token 消耗。
5. 真正的字段抽取准确率暂不可签字；应在结构化抽取接口完成后，用字段级 Precision、Recall、F1、Exact Match 和证据 span 命中率验收。
6. 所有测试完成后，将报告路径和结论回填 `docs/specs/final-acceptance-traceability.md`，再把对应 Spec 从 `IMPLEMENTED_PENDING_EVAL` 改为 `DONE`。
