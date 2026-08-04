# AIOps 修复闭环 Spec

状态：`DONE`  
范围：修复后验证、失败转人工、MTTR 指标  
现有基础：AIOps Event、白名单 Runbook、dry-run、ADMIN 执行权限、幂等键、Action 持久化、知识库 Repair API

验收证据（2026-07-27）：`eval/reports/aiops_remediation_loop_eval_report.md`，16/16 通过，闭环通过率 100%，P95=124.4ms。

## 1. 背景与目标

当前 Runbook 能执行知识库修复，并检查 DB、ES、Milvus 是否一致，但事件审批、验证失败转人工和 MTTR 统计还没有形成统一状态机。本 Spec 将执行动作升级为可审计的故障处置闭环。

## 2. 范围

本期包含：

- INTERNAL 可诊断、查看计划和指标，但不能执行修复。
- ADMIN 审批并执行白名单 Runbook。
- 每次真实执行必须记录审批人、执行人、traceId、幂等键及前后快照。
- Runbook 完成后自动验证；验证失败转人工，不能标记为已解决。
- 统计已解决事件的 MTTR、P50、P95 和自动修复成功率。

本期不包含任意 Shell、任意 SQL、任意容器重启和高风险生产操作。

## 3. 状态机

```text
OPEN -> DIAGNOSED -> PENDING_APPROVAL -> APPROVED
     -> EXECUTING -> VERIFYING -> RESOLVED
                              -> ESCALATED
     -> REJECTED
```

- `RESOLVED` 只能由验证通过或 ADMIN 手工覆盖产生。
- 手工覆盖必须保存原因，且不计入自动修复成功率。
- `ESCALATED` 表示需要人工处理，必须包含 `escalationReason`。

## 4. 功能需求

### AIOPS-FR-001 审批

真实执行前事件必须处于 `APPROVED`，审批接口仅 ADMIN 可用。dry-run 不需要审批。

### AIOPS-FR-002 受控执行

继续复用 `/api/ai/aiops/runbooks/execute`。只允许注册在后端目录中的 Runbook，模型仅能选择 `actionCode` 和受校验参数。

### AIOPS-FR-003 自动验证

修复结束后状态进入 `VERIFYING`：

- `REPAIR_KNOWLEDGE_DOC`：DB、ES、Milvus 数量及 chunkId 全部一致。
- `REPAIR_KNOWLEDGE_BATCH`：没有 FAILED 任务，且目标文档全部一致。
- 验证通过更新事件为 `RESOLVED`；否则更新为 `ESCALATED`。

### AIOPS-FR-004 失败转人工

执行异常、超时或验证失败时，保存错误、前后快照、失败阶段和建议动作，并将 `manualRequired=true`。

### AIOPS-FR-005 MTTR

事件保存 `resolvedAt`，指标定义如下：

```text
event_mttr_ms = resolved_at - first_seen_at
auto_repair_success_rate = 自动验证通过事件数 / 实际执行自动修复事件数
escalation_rate = ESCALATED 事件数 / 实际执行事件数
```

MTTR 只统计状态为 `RESOLVED` 且时间完整的事件，按自然时间窗口计算平均值、P50、P95。

## 5. 数据模型增量

`aiops_event` 增加：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| approved_by | BIGINT | 审批用户 |
| approved_at | TIMESTAMP | 审批时间 |
| resolved_at | TIMESTAMP | 验证通过或手工解决时间 |
| resolution_type | VARCHAR(32) | AUTO_VERIFIED / MANUAL_OVERRIDE |
| manual_required | BOOLEAN | 是否转人工 |
| escalation_reason | TEXT | 转人工原因 |

`aiops_action` 增加：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| trace_id | VARCHAR(128) | 本次处置链路 |
| idempotency_key | VARCHAR(160) | 唯一幂等键 |
| verification_status | VARCHAR(32) | NOT_STARTED / PASSED / FAILED |
| started_at | TIMESTAMP | 执行开始 |
| finished_at | TIMESTAMP | 执行结束 |

对 `(event_id, action_type, idempotency_key)` 建唯一索引。

## 6. API 契约

```http
POST /api/ai/aiops/events/{eventId}/approve
POST /api/ai/aiops/events/{eventId}/reject
GET  /api/ai/aiops/events/{eventId}/remediation
GET  /api/ai/aiops/metrics/mttr?from=&to=&source=
```

审批请求：

```json
{
  "reason": "确认仅重建 docId=19 的检索索引"
}
```

MTTR 响应至少包含：`resolvedCount`、`averageMttrMs`、`p50MttrMs`、`p95MttrMs`、`autoRepairSuccessRate`、`escalationRate`。

## 7. 安全与异常规则

- INTERNAL：查看事件、dry-run、指标；不能 approve 或真实 execute。
- ADMIN：审批、执行、手工覆盖和转人工确认。
- 同一幂等键重复请求返回原 Action，不再次执行。
- 客户端不得直接把状态更新为 `RESOLVED`。
- 验证失败时 HTTP 可返回业务成功，但 Action 必须是 `FAILED`、Event 必须是 `ESCALATED`，消息不能写“修复成功”。
- 所有执行参数和快照进入审计 metadata，敏感字段脱敏。

## 8. 验收标准

### AIOPS-AC-001 正常闭环

Given 存在不一致事件且 ADMIN 已审批，When 执行单文档修复，Then 必须产生 traceId、前后快照，验证一致后事件为 `RESOLVED` 并计算 MTTR。

### AIOPS-AC-002 权限拒绝

Given INTERNAL 用户，When 尝试审批或真实执行，Then 返回 403，且无 Action 被执行。

### AIOPS-AC-003 验证失败转人工

Given 修复后仍不一致，When 自动验证结束，Then Action 为 `FAILED`、Event 为 `ESCALATED`、`manualRequired=true`，并记录原因。

### AIOPS-AC-004 幂等

Given 相同 eventId、actionCode 和 idempotencyKey，When 重复执行，Then 只产生一次副作用并返回首次 Action。

### AIOPS-AC-005 MTTR 准确性

Given 三个有确定时间的已解决事件，When 查询 MTTR，Then平均值、P50、P95 与离线计算误差不超过 1 ms，未解决事件不进入样本。

## 9. 评测映射

新增：

```text
eval/datasets/aiops_remediation_loop_eval_dataset.json
tools/aiops_remediation_loop_eval.py
```

至少覆盖 15 条：成功 4、权限 3、验证失败 3、幂等 2、MTTR 3。报告必须输出 Closure Rate、Escalation Accuracy、Idempotency Pass Rate、MTTR Accuracy 和 P95 API Latency。
