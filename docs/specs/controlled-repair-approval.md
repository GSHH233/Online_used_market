# Repair 与自动修复审批 Spec

状态：`DONE`

验收进度（2026-07-27）：

- `APPROVAL-AC-001/002/003`、`SECURITY-01` 已由 `eval/reports/controlled_operation_eval_report.md` 验证，12/12 用例通过，P95=38.7ms。
- `APPROVAL-AC-004/005` 已由 `eval/reports/aiops_remediation_loop_eval_report.md` 验证，16/16 用例通过，失败转人工与幂等副作用均符合预期，P95=124.4ms。

范围：统一 Repair 与 AIOps 写操作审批、风险分级、执行后验证、失败转人工。

现有基础：AIOps Event、`PENDING_APPROVAL`/`APPROVED` 状态、ADMIN 审批、白名单 Runbook、幂等键、Repair API、修复后验证、ESCALATED 与 MTTR。

前端合同：见 [项目收尾前端实现补充 Spec](./frontend-closure-contract.md) 第2、3、5节。

## 1. 背景与目标

当前 AIOps Runbook 已有审批能力，但知识库 Repair API 仍可能被当作独立管理操作直接调用，容易形成两套写操作入口。本 Spec 将所有会改变 DB、ES、Milvus 或事件状态的动作纳入统一受控执行链路。

本项目统一使用 `PENDING_APPROVAL`，不新增同义的 `WAITING_APPROVAL`。

## 2. 状态机

```text
DRAFT -> DIAGNOSED -> PENDING_APPROVAL -> APPROVED
      -> REJECTED                    -> EXECUTING
                                      -> VERIFYING -> RESOLVED
                                                   -> ESCALATED
```

## 3. 功能需求

### APPROVAL-FR-001 风险分级

- LOW：只读查询、dry-run、一致性检查，可自动执行。
- MEDIUM：单文档 Repair、reindex，必须 ADMIN 审批。
- HIGH：批量写入、大范围重建、任意命令，项目 MVP 不自动执行，只允许转人工。

风险等级由后端白名单配置产生，客户端和模型不得自行降低。

### APPROVAL-FR-002 统一受控动作

知识库 Repair、reindex 和 AIOps Runbook 的真实写操作必须关联 `eventId`、`traceId`、`actionCode` 和 `idempotencyKey`。前端不得绕过审批直接调用底层执行服务。

保留底层 Repair API 供系统内部调用，但生产安全配置下仅允许受控执行服务访问。

### APPROVAL-FR-003 审批快照

提交审批时保存动作、参数、目标资源、风险等级、执行计划和诊断证据。审批后执行必须使用该快照；若参数改变，原审批失效并重新进入 `PENDING_APPROVAL`。

### APPROVAL-FR-004 执行与验证

批准后通过 Harness 执行白名单动作。完成后必须验证：

- Repair/reindex：DB、ES、Milvus 数量及 chunkId 对齐。
- AIOps 事件：对应健康指标恢复或业务验证器通过。
- 验证失败：Action 为 FAILED，Event 为 ESCALATED，并记录转人工原因。

### APPROVAL-FR-005 幂等与审计

相同 `(eventId, actionCode, idempotencyKey)` 只允许产生一次副作用。审批人、执行人、前后快照、验证结果和异常必须落库并可通过 traceId 查询。

## 4. API 契约

```http
POST /api/ai/operations/plans
POST /api/ai/operations/{operationId}/approve
POST /api/ai/operations/{operationId}/reject
POST /api/ai/operations/{operationId}/execute
GET  /api/ai/operations/{operationId}
```

现有 `/api/ai/knowledge/repair/**` 和 `/api/ai/aiops/runbooks/execute` 作为内部执行能力逐步收口到该门面之后。

## 5. 验收标准

### APPROVAL-AC-001 未审批禁止执行

Given MEDIUM 风险 Repair 计划未审批，When 请求执行，Then 返回冲突或拒绝，且三存储无变化。

### APPROVAL-AC-002 参数防篡改

Given 已批准 docId=19，When 执行请求改为 docId=20，Then 原审批不可使用并记录拒绝原因。

### APPROVAL-AC-003 修复后验证

Given 单文档索引不一致，When ADMIN 批准并执行，Then 验证三存储一致后才进入 RESOLVED。

### APPROVAL-AC-004 失败转人工

Given Milvus 不可用，When Repair 执行或验证失败，Then 进入 ESCALATED，保留失败阶段、异常和恢复建议。

### APPROVAL-AC-005 重复请求幂等

Given 相同幂等键重复执行，Then 返回首次 Action，不重复写入和重建索引。

## 6. 评测映射

在 `aiops_remediation_loop_eval` 基础上增加 Repair 绕过、参数篡改、审批过期、重复执行和验证失败用例。关键指标为 Unauthorized Execution Count=0、Idempotency Pass Rate=100%、Verification Coverage=100% 和 Escalation Accuracy=100%。
