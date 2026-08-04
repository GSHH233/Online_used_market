# Durable Agent Runtime MVP Spec

状态：`DONE`

更新时间：2026-07-12

验收证据（2026-07-27）：`eval/reports/durable_runtime_eval_report.md`，15/15 通过，恢复成功率 100%，重复副作用数 0，P95 case latency=10068.0ms。该报告验收 Runtime 正确性，不代表 Query Rewrite 成功率或端到端性能 SLA 已验收。

## 1. 目标与边界

在现有 Graph/DAG Agent 与 Tool Execution Harness 外增加运行级持久化，使长任务在失败或服务重启后能够从最近 checkpoint 恢复，并避免重复执行已成功工具节点。

本期是 Durable Runtime MVP，不建设通用 BPMN 平台，不允许任意动态代码执行，不迁移普通 RAG Chat，也不允许 Runtime 绕过权限、Harness 或受控操作审批。

## 2. 状态模型

Run：`CREATED -> PLANNING -> RUNNING -> COMPLETED`，异常可进入 `FAILED`，恢复过程为 `FAILED -> RESUMING -> RUNNING`；运行中支持 `PAUSED` 与 `CANCELLED`。

Node：`PENDING -> RUNNING -> SUCCEEDED/FAILED`。恢复时已 `SUCCEEDED` 的工具节点直接装配历史 observation，不再次调用工具。

## 3. 已实现能力

- PostgreSQL 持久化 `agent_runtime_run`、`agent_runtime_node`、`agent_runtime_checkpoint`。
- Graph 执行观察器记录计划、节点开始、节点结果及 checkpoint。
- 通过 `version` 和 `lease_until` 控制恢复所有权，定时扫描租约过期的运行。
- 支持暂停、恢复、取消、快照重放和只读实时重放。
- 快照重放只复用历史 observation；实时重放重新执行只读工具。
- INTERNAL 可创建和查看；仅 ADMIN 可暂停、恢复、取消和重放。
- 故障注入默认关闭，仅在 `ai.agent.runtime.fault-injection-enabled=true` 时用于评测。

## 4. API

```http
POST /api/ai/agent/runs
GET  /api/ai/agent/runs?limit=30
GET  /api/ai/agent/runs/{runId}
POST /api/ai/agent/runs/{runId}/pause
POST /api/ai/agent/runs/{runId}/resume
POST /api/ai/agent/runs/{runId}/cancel
POST /api/ai/agent/runs/{runId}/replay?liveReadonly=false
GET  /api/ai/agent/runs/{runId}/checkpoints
```

## 5. 前端

- `/ai/runtime`：运行列表、新建任务、状态统计。
- `/ai/runtime/:runId`：结果、节点、observation、checkpoint 和生命周期操作。
- 页面仅对 INTERNAL/ADMIN 显示；生命周期按钮仅 ADMIN 可见。

## 6. 验收标准

- `RUNTIME-AC-001`：故障后恢复时，已成功工具节点 attemptCount 不增加。
- `RUNTIME-AC-002`：同一过期 Run 只能由一个实例获取 lease。
- `RUNTIME-AC-003`：USER 无权访问，INTERNAL 无权执行生命周期写操作。
- `RUNTIME-AC-004`：快照重放工具 attemptCount 为 0，实时只读重放至少执行一个工具。
- `RUNTIME-AC-005`：Run、Node、Checkpoint 和 traceId 可关联查询。
- `FE-AC-005`：前端按钮与运行状态、角色一致。

## 7. 评测

使用 `eval/datasets/durable_runtime_eval_dataset.json` 与 `tools/durable_runtime_eval.py`。当前 15 条用例覆盖正常完成、权限、暂停恢复、故障恢复、节点去重、checkpoint、快照重放和实时重放。

在线评测前需显式开启故障注入：

```powershell
$env:AI_AGENT_RUNTIME_FAULT_INJECTION_ENABLED="true"
```

只有生成全通过报告后，状态才可从 `IMPLEMENTED_PENDING_EVAL` 更新为 `DONE`。
