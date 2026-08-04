# Traceability And Eval Spec

状态：`IMPLEMENTED`

范围：Agent Trace 持久化、Eval Run 结果正规化、业务闭环 traceId 串联、评测报告落库。

现有基础：`ai_agent_trace_run`、`ai_agent_trace_step`、`ai_eval_run`、`ai_eval_metric`、`ai_eval_case_result`、`ai_rag_chat_turn_audit`、AIOps Event/Action trace 字段、Knowledge Gap Regression evalRunId。

## 1. 背景与目标

AI 应用链路如果只看最终回答，很难判断问题出在检索、工具选择、权限、模型回答、回归测试还是业务回流。  
本 Spec 规定所有关键 AI 链路必须写入可追踪表，形成“请求 -> 推理/工具步骤 -> 业务结果 -> 评测结果”的可审计闭环。

目标：

- 面试或调试时可以通过 `traceId` 还原一次 AI 调用的完整过程。
- 自动化评测结果不只停留在 markdown 报告，还要进入数据库指标表。
- 知识缺口、发布反馈、AIOps 修复等业务闭环都能关联到 trace/eval。
- 出错时保留异常、阶段、工具、耗时和上下文摘要，便于定位。

## 2. 核心链路

```text
用户请求 / 评测脚本
-> 生成 traceId
-> 写入 ai_agent_trace_run 或业务审计表
-> 每次工具调用 / 推理步骤写入 ai_agent_trace_step
-> 业务表记录 traceId / evalRunId / actionId
-> 评测脚本生成 report
-> report 汇总写入 ai_eval_run / ai_eval_metric / ai_eval_case_result
-> Eval Dashboard 查询数据库展示
```

## 3. 功能需求

### TRACE-FR-001 Trace ID 生成与透传

所有 AI 主链路必须具备 `traceId`：

- RAG Chat：返回 `traceId`，并写入 RAG turn audit。
- ReAct Agent / Graph Agent：返回 `traceId`，并写入 trace run/step。
- AIOps Runbook：执行 action 时生成 `traceId`，写入 event/action。
- 发布助手：生成 `assistId` 与 `traceId`，写入发布建议快照。
- 知识缺口：沉淀 gap 时保存 `latestTraceId`，回归时保存 regression traceId。

客户端可以传入 traceId 用于调试，但生产链路以后端生成为准；后端不得信任客户端传入的身份、权限和执行结果。

### TRACE-FR-002 Trace Run 持久化

每次 Agent 调用必须写入 `ai_agent_trace_run`，至少包含：

- `trace_id`
- `session_id`
- `agent_type`
- `mode`
- `question`
- `answer`
- `status`
- `success`
- `elapsed_ms`
- `step_count`
- `observation_count`
- `source_count`
- `tools`
- `error_msg`
- `metadata_json`

异常也必须落库，不能只打印日志。

### TRACE-FR-003 Trace Step 持久化

每个推理/工具步骤必须写入 `ai_agent_trace_step`，至少包含：

- `trace_id`
- `step_index`
- `phase`
- `tool`
- `status`
- `thought`
- `action`
- `observation`
- `elapsed_ms`
- `source_doc_ids_json`
- `metadata_json`

其中 `thought` 只保存可展示的简化推理摘要，不保存模型完整隐式思维链。

### TRACE-FR-004 Eval Run 正规化

每套评测脚本产出的报告必须能汇总为：

- `ai_eval_run`：评测类型、报告文件、状态、总数、通过数、成功率、P50、P95、指标 JSON。
- `ai_eval_metric`：可聚合指标，例如 Recall@5、MRR、Source Hit、Reject Accuracy、MTTR、P95。
- `ai_eval_case_result`：caseId、状态、耗时、失败原因。

Eval Dashboard 以数据库为主，不依赖扫描根目录零散报告。

### TRACE-FR-005 业务闭环关联

业务表必须保存能反查 trace/eval 的字段：

- `ai_publish_assist_record.trace_id`
- `aiops_event.trace_id`
- `aiops_action.trace_id`
- `ai_knowledge_gap.latest_trace_id`
- `ai_knowledge_gap_regression.trace_id`
- `ai_knowledge_gap_regression.eval_run_id`
- `ai_rag_chat_turn_audit.trace_id`

业务页面中展示的记录必须能跳转或复制 `traceId`，方便排查。

## 4. 数据模型

### ai_agent_trace_run

记录一次 Agent 调用的整体结果。用于回答“这次调用成功了吗、用了哪些工具、耗时多少、有没有异常”。

### ai_agent_trace_step

记录一次 Agent 调用中的步骤。用于回答“模型选择了什么工具、工具返回了什么 observation、最终回答有没有引用 observation”。

### ai_eval_run

记录一次评测运行。用于回答“这套评测整体是否通过、关键指标是多少、报告文件是哪一个”。

### ai_eval_metric

记录评测中的结构化指标。用于 Dashboard 画趋势，不依赖解析 markdown。

### ai_eval_case_result

记录单条 case 的结果。用于定位失败用例。

### ai_rag_chat_turn_audit

记录 RAG 对话是否拒答、低置信、source 数、检索模式等信息。用于知识缺口沉淀和拒答率统计。

## 5. API 契约

```http
GET /api/ai/agent/traces?traceId=&agentType=&status=&limit=
GET /api/ai/agent/traces/{traceId}
GET /api/ai/agent/eval-dashboard?recentLimit=
```

相关业务接口返回体中应包含：

```json
{
  "traceId": "后端生成的链路 ID"
}
```

## 6. 安全与隐私

- Trace 表只允许 INTERNAL/ADMIN 查询。
- `thought` 字段只能保存可审计摘要，不能保存完整隐式推理链。
- `observation` 和 `metadata_json` 中的 token、手机号、邮箱等敏感信息必须脱敏。
- USER 不能通过 traceId 查询内部工具结果。
- 业务 traceId 不能作为权限凭据，只能作为排查索引。

## 7. 验收标准

### TRACE-AC-001 Trace 可还原

Given 一次 ReAct 或 Graph Agent 调用，When 查询 `/api/ai/agent/traces/{traceId}`，Then 能看到 run 和按顺序排列的 step。

### TRACE-AC-002 异常可追踪

Given 工具调用失败，When Agent 返回降级回答或错误，Then trace run 的 `status/success/errorMsg` 必须反映失败原因。

### TRACE-AC-003 评测可入库

Given 执行任意评测脚本，When Eval Dashboard 刷新，Then `ai_eval_run` 中存在对应评测记录，且 total/passed/p95 等指标可查询。

### TRACE-AC-004 业务记录可反查

Given 发布助手、知识缺口或 AIOps 事件产生业务记录，When 查看该记录，Then 至少能得到一个可查询的 `traceId` 或 `evalRunId`。

### TRACE-AC-005 权限隔离

Given USER 访问 trace 管理接口，Then 返回 403；INTERNAL/ADMIN 可以按权限查看。

## 8. 评测映射

现有评测脚本应覆盖：

- `tools/react_agent_eval.py`：trace 完整率、observation 使用率。
- `tools/aiops_runbook_eval.py`：action traceId、修复后验证、MTTR。
- `tools/knowledge_gap_eval.py`：gap latestTraceId、regression evalRunId。
- `tools/publish_feedback_eval.py`：assistId/traceId 快照与反馈关联。

报告文件可以保留，但最终看板数据以 `ai_eval_*` 表为准。
