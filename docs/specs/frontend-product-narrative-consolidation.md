# 前端产品叙事收口 Spec

状态：`IMPLEMENTED_PENDING_LIVE_ACCEPTANCE`

更新时间：2026-07-27

## 1. 背景

项目已经具备 RAG、ReAct、Graph/DAG、Memory、Harness、Runtime、AIOps 与知识治理能力，但此前前端按技术模块拆页，存在以下问题：

1. 用户需要先理解技术名词，才能判断应该进入哪个页面。
2. 同一业务链路被拆成多个入口，需要手工复制 `docId`、`assistId`、`traceId` 或 `runId`。
3. 主结果区域直接展示 JSON、检索参数和工具轨迹，难以说明项目解决了什么业务问题。
4. 普通用户、内部运营和管理员的入口边界不清晰。

本次收口不删除后端能力与评测脚本，只调整业务入口、页面职责和结果表达。

## 2. 产品主线

前端只围绕四个可感知功能组织：

| 功能 | 用户问题 | 生产入口 | 后端能力 |
| --- | --- | --- | --- |
| AI 商品发布助手 | 商品怎么写、有哪些发布风险 | `/products/publish` | RAG、文案优化、风险证据、反馈回流 |
| 交易问答 | 平台规则和交易问题怎么查 | `/ai/rag-chat` | Query Rewrite、Hybrid + RRF、拒答 |
| 知识治理 | 文档如何上传、更新、补缺和修复 | `/ai/knowledge` | 解析切片、异步入库、缺口治理、三存储一致性 |
| 智能排查 | 内部异常如何定位和受控处理 | `/ai/investigate` | DIRECT/ReAct/DAG、Harness、审批、Runtime |

对外讲述顺序固定为：

```text
发布助手 -> 交易问答 -> 知识治理 -> 智能排查
```

技术名词只用于解释实现，不作为普通用户的操作入口。

## 3. 角色与导航

### 3.1 USER

商城主导航最多四项：

```text
首页 / 逛商品 / 发布 / 订单
```

交易问答和收藏作为辅助入口。USER 不展示知识库、AIOps、Runtime、ReAct、DAG、Harness、Memory、Repair 与评测入口。

### 3.2 INTERNAL / ADMIN

运营后台一级导航最多四项：

```text
运营总览 / 知识治理 / 智能排查 / 运行记录
```

- INTERNAL：查看治理、排查、计划和运行记录，只能执行只读能力。
- ADMIN：可以审批、拒绝、执行受控操作，以及恢复、取消和重放 Runtime。
- 前端隐藏不能替代后端权限校验。

## 4. 页面规格

### 4.1 AI 商品发布助手

目标路由：`/products/publish`

流程：

```text
填写商品 -> AI 检查 -> 查看文案与风险证据
-> 逐项采纳 -> 发布商品 -> 自动关联 assistId 和采纳字段
```

要求：

- AI 检查并入商品发布页，不再使用独立发布助手页面。
- 标题和描述建议可以分别采纳。
- `assistId`、采纳类型和采纳字段在发布时自动携带。
- 模型不可用时明确显示规则/证据降级，不伪装成模型生成。
- 发布成功后跳转商品详情，不展示技术响应对象。

### 4.2 交易问答

目标路由：`/ai/rag-chat`

要求：

- 默认使用聊天交互，不展示 TopK、RRF、向量模式和 Rewrite 开关。
- 展示回答与来源规则；证据不足时给出拒答或补充信息提示。
- 流式生成期间可停止，不允许重复提交。
- INTERNAL 可以展开“检索过程”，但只展示链路、来源数、回答方式与事件摘要，不展示 Raw Payload。

### 4.3 知识治理

目标路由：`/ai/knowledge`

页签：

```text
文档管理 / 上传入库 / 知识缺口 / 检索验证 / 存储健康
```

要求：

- 文档管理支持按标题和状态查询，并通过文档名称选择治理对象。
- 上传支持 MD、PDF、DOC、DOCX，展示上传进度和异步任务阶段。
- 任务采用递增轮询间隔，在成功、失败或超限后停止。
- 知识缺口支持分诊、关联文档和回归验证，回归通过后才关闭。
- 检索验证仅 ADMIN 可见，默认展示 Top 5 和可读的改写过程。
- 存储健康展示 DB、ES、Milvus 数量与差异，Repair 只能创建受控计划，不能直接执行。

### 4.4 智能排查

目标路由：`/ai/investigate`

流程：

```text
输入问题 -> 自动路由策略 -> 收集证据 -> 生成结论
-> 创建处理计划 -> ADMIN 审批 -> 确认执行 -> 验证或转人工
```

要求：

- 用户不能手工选择 DIRECT、ReAct 或 DAG。
- 默认先展示结论、关键证据和建议动作。
- 执行策略、步骤数量和图节点放在技术详情折叠区。
- 工具返回使用摘要，不在主区域直接输出原始 rows 或 JSON。
- 受控操作状态以现有后端契约为准：`PENDING_APPROVAL -> APPROVED -> RESOLVED/FAILED`。
- INTERNAL 只读；ADMIN 才能批准、拒绝和执行。

### 4.5 运营总览

目标路由：`/ai/eval-dashboard`

默认只展示四组指标：

1. 发布助手：使用次数、建议采纳率、风险反馈准确率。
2. 智能检索：Recall@5、拒答准确率、待处理知识缺口。
3. 知识治理：有效文档、失败任务、一致性通过率。
4. 智能排查：任务成功率、待审批计划、P95 MTTR。

Trace、Harness、Rewrite 延迟和回归报告进入“工程指标与最近回归”折叠区。

### 4.6 运行记录

目标路由：`/ai/runtime`、`/ai/runtime/:runId`

- 列表只承担审计与故障恢复，不在此创建新排查任务。
- 详情展示业务问题、最终结果、节点摘要和恢复点。
- INTERNAL 只读，ADMIN 可以暂停、恢复、取消和重放。
- 节点 Observation 转成摘要，不直接展示整段 JSON。

## 5. 路由迁移

| 旧路由 | 目标 |
| --- | --- |
| `/ai/publish-assist` | `/products/publish?ai=1` |
| `/ai/knowledge-gaps` | `/ai/knowledge?tab=gaps` |
| `/ai/storage-consistency` | `/ai/knowledge?tab=storage` |
| `/ai/retrieval-debug` | `/ai/knowledge?tab=retrieval` |
| `/ai/publish-feedback` | `/ai/eval-dashboard?section=publish` |
| `/ai/aiops` | `/ai/investigate?tab=operations` |

以下路由仅在 `VITE_ENABLE_AI_DEBUG=true` 且角色为 ADMIN 时注册：

- `/ai/react-agent`
- `/ai/graph-agent`
- `/ai/harness`
- `/ai/memory-debug`

## 6. API 收口

新增按业务域拆分的 API 模块：

```text
api/modules/publishAssistant.ts
api/modules/knowledge.ts
api/modules/investigation.ts
api/modules/operations.ts
api/modules/runtime.ts
```

要求：

- 新增接口使用 TypeScript interface，不继续扩张单体 `aiApi`。
- 统一复用 Axios 的 `Result<T>` 解包、JWT、401、超时和网络错误处理。
- 文件上传支持进度回调和取消信号。
- 后端保留旧接口，新增文档列表、文档详情、受控操作列表与知识治理存储路径。
- 发布助手和 RAG Chat 返回 `generationMode`、`fallbackUsed`、`fallbackReason`，让降级状态可见。

## 7. 验收追踪

| 编号 | 验收项 | 状态 | 证据 |
| --- | --- | --- | --- |
| PN-AC-001 | USER 导航只包含商城任务 | PASS | `MainLayout.vue` |
| PN-AC-002 | 后台一级导航不超过四项 | PASS | `MainLayout.vue` |
| PN-AC-003 | 发布页完成生成、逐项采纳和 assistId 自动透传 | PASS | `ProductPublishView.vue`、`ProductCreateDTO` |
| PN-AC-004 | 交易问答不展示检索参数和 Raw Payload | PASS | `RagChatView.vue` |
| PN-AC-005 | 知识治理聚合上传、任务、缺口、检索和存储健康 | PASS | `KnowledgeView.vue` |
| PN-AC-006 | 排查页聚合证据、计划、审批和执行 | PASS | `AgentInvestigationView.vue` |
| PN-AC-007 | 技术页只在 ADMIN Debug 模式注册 | PASS | `router/index.ts` |
| PN-AC-008 | 四条主流程不直接输出完整响应 JSON | PASS | 静态扫描 |
| PN-AC-009 | USER/INTERNAL/ADMIN 路由与按钮权限一致 | PASS | 路由守卫、后端 `ToolAccessGuard` |
| PN-AC-010 | 375px 与 1440px 无水平溢出 | PASS | 浏览器视口检查 |
| PN-AC-011 | 前端生产构建通过、后端测试通过 | PASS | `npm run build`、`mvnw test` |
| PN-AC-012 | 五分钟演示只经过四条业务主线 | PENDING | 需要真实服务与三角色账号最终人工验收 |

## 8. 完成定义

代码实现已完成。满足以下条件后将状态改为 `DONE`：

1. 使用 USER、INTERNAL、ADMIN 三个真实账号完成按钮权限验收。
2. 使用真实后端完成文件上传、缺口回归、Repair 审批执行和 Runtime 恢复。
3. 按四条业务主线完成一次五分钟演示，且无控制台错误。
