# 项目收尾前端实现补充 Spec

状态：`IMPLEMENTED_PENDING_EVAL`

范围：智能排查、受控 Repair/AIOps、Durable Agent Runtime、面试演示模式的 Vue 组件、路由、角色权限、API 字段与页面状态。

关联 Spec：`agent-investigation-routing.md`、`controlled-repair-approval.md`、`durable-agent-runtime.md`、`demo-mode.md`。

> 2026-07-12 范围变更：用户明确取消 Demo Center 页面。本 Spec 中 `/ai/demo`、`DemoCenterView.vue`、Seed/Reset UI 不再作为本期验收项；以 `docs/FIVE_MINUTE_DEMO_CHECKLIST.md` 作为演示交付物。

## 1. 目标与原则

本 Spec 是四份后端/业务 Spec 的前端实现合同。页面只表达用户任务，不把 ReAct、DAG、Harness、Checkpoint 等内部技术名词作为一级业务功能。

- USER 只看到商城、发布助手和交易问答。
- INTERNAL 看到智能排查、知识治理、只读状态和 Trace。
- ADMIN 在 INTERNAL 能力上增加审批、执行、恢复和演示环境管理。
- 前端角色判断只控制可见性；后端仍必须再次鉴权。
- 所有写操作按钮必须根据后端状态显示，不能只根据本地状态推断。

## 2. 路由合同

| 路由 | 页面组件 | USER | INTERNAL | ADMIN | 导航可见性 | 当前状态 |
| --- | --- | --- | --- | --- | --- | --- |
| `/ai/investigate` | `AgentInvestigationView.vue` | 重定向 `/market` | 可用 | 可用 | 内部导航“智能排查” | 已实现，待评测 |
| `/ai/react-agent` | `ReactAgentView.vue` | 禁止 | 调试可用 | 调试可用 | 不进入导航 | 已实现兼容路由 |
| `/ai/graph-agent` | `GraphAgentView.vue` | 禁止 | 调试可用 | 调试可用 | 不进入导航 | 已实现兼容路由 |
| `/ai/aiops` | `AiopsRunbookView.vue` | 禁止 | 查看、建计划 | 审批与执行 | 内部导航“AIOps” | 部分实现 |
| `/ai/runtime` | `AgentRunListView.vue` | 禁止 | 查看 | 查看、恢复 | 内部导航“任务运行” | 待实现 |
| `/ai/runtime/:runId` | `AgentRunDetailView.vue` | 禁止 | 查看节点/Checkpoint | 暂停、恢复、取消、重放 | 由列表进入 | 待实现 |
| `/ai/demo` | `DemoCenterView.vue` | 禁止 | 查看健康状态 | Seed、Reset、执行旅程 | 内部导航“演示中心” | 待实现 |

### FE-FR-001 路由守卫

`router/index.ts` 继续使用 `meta.requiresInternal`，并新增 `meta.requiresAdmin`。守卫顺序固定为：未登录 -> guest 重定向 -> INTERNAL 校验 -> ADMIN 校验。

```ts
if (to.meta.requiresAdmin && authStore.normalizedRole !== 'ADMIN') return '/ai/eval-dashboard'
```

按钮隐藏不能代替路由守卫，路由守卫也不能代替后端权限校验。

## 3. 导航与布局组件

### `MainLayout.vue`

职责：按角色生成一级商城导航和内部控制台导航。

内部导航目标顺序：

```text
评测看板 -> 智能排查 -> AIOps -> 任务运行 -> 知识库 -> 知识缺口 -> 发布反馈 -> 存储一致性 -> 演示中心
```

字段/状态：

| 来源 | 字段 | 用途 |
| --- | --- | --- |
| `authStore` | `normalizedRole` | USER/INTERNAL/ADMIN 文案和权限 |
| `authStore` | `isInternal` | 是否显示内部导航 |
| `route.path` | 当前路由 | 激活菜单与内部导航显示 |

验收：普通用户页面不出现 ReAct、Graph、AIOps、Trace、Repair、Runtime 等入口；移动端内部功能通过“运营后台”进入，不塞入商城底部导航。

## 4. 智能排查页面

### 4.1 页面组件树

```text
AgentInvestigationView.vue
├─ InvestigationQueryForm.vue
├─ InvestigationStrategyBar.vue
├─ InvestigationAnswerPanel.vue
├─ InvestigationEvidenceList.vue
├─ AgentTraceTimeline.vue
└─ DagNodeStatusList.vue
```

第一期允许以上子组件内联在页面中；进入 `DONE` 前必须至少拆出 QueryForm、EvidenceList 和 TraceTimeline，避免 Runtime 页面重复实现。

### 4.2 请求字段

`POST /api/ai/agent/investigate`

| 前端字段 | 类型 | 必填 | 校验 | 后端字段 |
| --- | --- | --- | --- | --- |
| `question` | string | 是 | trim 后 2-2000 字 | `question` |
| `sessionId` | string | 否 | 最长 128 | `sessionId` |
| `maxSteps` | number | 是 | 1-8，默认 6 | `maxSteps` |

禁止前端发送 `mode`、`strategy`、`toolName` 和用户角色；这些值由后端身份与路由器决定。

### 4.3 响应字段与组件映射

| 响应字段 | 组件 | 展示规则 |
| --- | --- | --- |
| `traceId` | AnswerPanel | 可复制，不作为权限凭据 |
| `strategy` | StrategyBar | DIRECT/REACT/DAG 转为“直接查询/动态排查/并行排查” |
| `routeReason` | StrategyBar | 用业务语言说明选择原因 |
| `status`、`success` | StrategyBar | 文字 + 语义颜色，不只用颜色 |
| `answer` | AnswerPanel | 保留换行，禁止直接展示 JSON |
| `elapsedMs` | AnswerPanel | `<1000` 显示 ms，否则显示 s |
| `observations[]` | EvidenceList | 展示 tool、summary、total；原始 rows 默认折叠 |
| `agentTrace[]` | TraceTimeline | 展示 phase、tool、status、observation、elapsedMs |
| `graph[]` | DagNodeStatusList | DAG 时展开，DIRECT/REACT 默认折叠或隐藏 |
| `approvalRequired` | AnswerPanel | true 时显示“生成受控操作计划”，不显示直接执行 |

### FE-FR-002 页面状态

- `IDLE`：展示问题表单和三个示例问题。
- `LOADING`：提交按钮禁用，显示“正在收集证据”，保留输入。
- `SUCCESS`：先展示结论，再展示证据与轨迹。
- `DEGRADED`：展示可用结论，并标明哪些工具失败或使用 fallback。
- `ERROR`：显示原因和重试按钮，不清空问题。
- `EMPTY_EVIDENCE`：明确显示“未获得有效工具证据”，不能留白。

## 5. AIOps 与受控操作页面

### 5.1 组件树

```text
AiopsRunbookView.vue
├─ AiopsMetricStrip.vue
├─ AiopsEventList.vue
├─ AiopsEventDetail.vue
├─ OperationPlanForm.vue
├─ OperationApprovalPanel.vue
├─ OperationParameterSnapshot.vue
├─ OperationExecutionPanel.vue
├─ VerificationResultPanel.vue
└─ OperationTimeline.vue
```

现有 `AiopsRunbookView.vue` 的直接 Runbook 表单需要迁移为受控操作流程；旧 dry-run 可保留在“高级调试”折叠区，真实执行不再允许前端自由编辑参数。

### 5.2 创建计划字段

`POST /api/ai/operations/plans`

| 字段 | 类型 | 来源 | 校验/交互 |
| --- | --- | --- | --- |
| `actionCode` | enum | 下拉选择 | `REPAIR_KNOWLEDGE_DOC` / `REPAIR_KNOWLEDGE_BATCH` / `MARK_EVENT_RESOLVED` |
| `params.docId` | number | 单文档 Repair 表单 | 正整数；仅单文档动作显示 |
| `params.forceReindex` | boolean | 开关 | 默认 false |
| `params.sampleDocs` | number | 批量动作 | 1-100；HIGH 风险只生成计划 |
| `reason` | string | 文本域 | 5-1000 字，说明触发原因 |
| `traceId` | string | 可选关联 | 默认不手填，可从智能排查结果带入 |

### 5.3 操作详情字段

| 响应字段 | 展示位置 | 规则 |
| --- | --- | --- |
| `operationId` | 标题/复制 | 页面主标识 |
| `traceId` | 审计信息 | 链接到 Trace 详情 |
| `actionCode` | 参数快照 | 转为中文动作名 |
| `riskLevel` | 风险标签 | LOW/MEDIUM/HIGH 均显示文字 |
| `status` | 状态条 | PENDING_APPROVAL/APPROVED/EXECUTING/VERIFYING/RESOLVED/ESCALATED/REJECTED |
| `params` | ParameterSnapshot | 只读 JSON 键值，不提供执行前编辑 |
| `approvedBy`、`approvedAt` | ApprovalPanel | 未审批时显示“待审批” |
| `manualRequired`、`escalationReason` | VerificationPanel | 转人工时强提醒 |
| `execution.status` | ExecutionPanel | SUCCESS/FAILED/SKIPPED |
| `execution.verificationStatus` | VerificationPanel | PASSED/FAILED/NOT_STARTED |
| `execution.result` | VerificationPanel | 优先结构化展示 counts/consistent/errorMsg |

### FE-FR-003 按钮状态矩阵

| 状态 | INTERNAL | ADMIN |
| --- | --- | --- |
| `PENDING_APPROVAL` | 只读 | 审批、拒绝 |
| `APPROVED` | 只读 | 执行 |
| `EXECUTING/VERIFYING` | 只读、刷新 | 只读、刷新，按钮禁用 |
| `RESOLVED` | 查看结果 | 查看结果 |
| `ESCALATED` | 查看原因 | 查看原因、转人工记录 |
| `REJECTED` | 查看拒绝原因 | 查看拒绝原因 |

审批弹窗必须展示不可修改的 actionCode、riskLevel、params 和 reason。HIGH 风险不显示“自动执行”。

## 6. Durable Runtime 页面

### 6.1 页面与组件

```text
AgentRunListView.vue
├─ RuntimeRunFilters.vue
└─ RuntimeRunTable.vue

AgentRunDetailView.vue
├─ RuntimeRunHeader.vue
├─ RuntimeActionBar.vue
├─ RuntimeNodeTable.vue
├─ CheckpointTimeline.vue
├─ RuntimeErrorPanel.vue
└─ ReplayDialog.vue
```

### 6.2 列表字段

`GET /api/ai/agent/runs?status=&strategy=&sessionId=&limit=`

| 字段 | 展示 |
| --- | --- |
| `runId` | 可复制，点击进入详情 |
| `traceId` | Trace 跳转 |
| `strategy` | DIRECT/REACT/DAG |
| `status` | 状态标签 |
| `question` | 最多两行，悬停显示全文 |
| `completedNodeCount/totalNodeCount` | 进度数字，不使用虚假百分比 |
| `createdTime/updatedTime` | 本地时间 |

### 6.3 详情字段和操作

`GET /api/ai/agent/runs/{runId}` 返回 run、nodes、latestCheckpoint。

节点表展示：`nodeId`、`toolName`、`dependencies`、`status`、`attemptCount`、`elapsedMs`、`errorMsg`。Observation 默认摘要，INTERNAL/ADMIN 手动展开后才加载完整脱敏内容。

操作 API：

| 操作 | API | 可用状态 | 权限 |
| --- | --- | --- | --- |
| 暂停 | `POST /runs/{runId}/pause` | RUNNING | ADMIN |
| 恢复 | `POST /runs/{runId}/resume` | PAUSED/FAILED | ADMIN |
| 取消 | `POST /runs/{runId}/cancel` | CREATED/PLANNING/RUNNING/PAUSED | ADMIN |
| 快照重放 | `POST /runs/{runId}/replay` mode=`REPLAY_WITH_SNAPSHOTS` | COMPLETED/FAILED | ADMIN |
| 只读实时重放 | 同上 mode=`REPLAY_LIVE_READONLY` | COMPLETED/FAILED | ADMIN |

所有操作先弹确认框；恢复与重放成功后跳转到新/当前 Run 详情。写节点未知结果时显示 `WAITING_APPROVAL`，不得提供“一键继续”。

## 7. 演示中心页面

### 7.1 组件树

```text
DemoCenterView.vue
├─ DemoFallbackBanner.vue
├─ DependencyHealthGrid.vue
├─ DemoSeedPanel.vue
├─ DemoJourneyStepper.vue
└─ DemoRunLog.vue
```

### 7.2 页面字段

| 字段 | 组件 | 规则 |
| --- | --- | --- |
| `demoProfile` | Banner | 必须为 true；否则禁止 Seed/Reset |
| `demoFallback` | Banner | true 时常驻警示，不计入正式评测 |
| `dependencies[].name/status/latencyMs/message` | HealthGrid | READY/DEGRADED/BLOCKED，提供重试 |
| `seedStatus/seededCounts` | SeedPanel | 展示账号、文档、商品、事件数量 |
| `journey.steps[]` | Stepper | WAITING/RUNNING/PASSED/FAILED/SKIPPED |
| `journey.traceIds[]` | RunLog | 每条链路可反查 Trace |
| `journey.totalElapsedMs` | RunLog | 结束后显示 |

Seed、Reset、执行固定旅程仅 ADMIN 可见。Reset 必须二次确认，并明确“只清理 demo 命名空间”。

## 8. 公共组件与字段规范

### `StatusChip.vue`

统一显示业务状态，必须同时包含文本和颜色。未知值显示 `UNKNOWN`，不得默认为成功。

### `TraceLink.vue`

接收 `traceId`，复制成功给出提示；仅 INTERNAL/ADMIN 提供 Trace 详情跳转。

### `JsonKeyValueView.vue`

用于只读参数与结果；默认结构化键值展示，只有“查看原始数据”才显示 JSON。敏感字段由后端脱敏，前端再屏蔽 key 包含 token、password、secret、authorization 的值。

### `AsyncStatePanel.vue`

统一处理 loading、empty、error 和 retry。禁止接口失败后显示空白列表或把失败当成“暂无数据”。

## 9. 非功能要求

### FE-NFR-001 可访问性

- 所有输入有可见 label。
- 图标按钮有 `aria-label`。
- 状态不能只用颜色表达。
- 弹窗关闭后焦点回到触发按钮。

### FE-NFR-002 响应式

- 375px 下无水平滚动。
- 节点表在移动端转为卡片，不压缩成长字符串。
- 关键执行按钮最小高度 44px。

### FE-NFR-003 错误与安全

- 401 统一退出并跳转登录。
- 403 显示“权限不足”，不显示“资源不存在”。
- 409 展示当前状态并引导刷新。
- 5xx 保留输入，提供重试和 traceId。
- 禁止使用 `v-html` 展示模型回答、日志和 Observation。

## 10. 前端验收标准

### FE-AC-001 角色导航隔离

三种角色登录后，导航和路由访问符合第2节矩阵；USER 无法通过手输 URL 打开内部页面。

### FE-AC-002 智能排查完整展示

执行 DIRECT、REACT、DAG 各一条用例，策略、原因、结论、证据、Trace 和状态均正确展示，页面不直接输出整段 JSON。

### FE-AC-003 审批参数不可变

创建 docId=19 的操作计划后，审批和执行页面只能读取快照，无法改成 docId=20。

### FE-AC-004 状态驱动操作

未审批不能执行；VERIFYING 时所有写按钮禁用；ESCALATED 显示转人工原因；HIGH 风险无自动执行按钮。

### FE-AC-005 Runtime 生命周期

暂停、恢复、取消与两种重放按钮仅在允许状态出现，操作后状态与后端一致。

### FE-AC-006 Demo 透明降级

`demoFallback=true` 时页面持续显示降级标识，结果不显示为正式评测通过。

### FE-AC-007 页面状态完整

每个新页面均通过 loading、empty、403、409、500 和成功状态检查。

## 11. 测试与证据

- 单元：路由守卫、状态按钮矩阵、字段格式化。
- 组件：表单校验、审批快照只读、错误重试。
- E2E：USER 越权、INTERNAL 排查、ADMIN 审批执行、Runtime 恢复、Demo 旅程。
- 视觉：375x812、768x1024、1440x900 截图。
- 每项证据写入 `final-acceptance-traceability.md`，未附证据不能标记 `PASS`。
