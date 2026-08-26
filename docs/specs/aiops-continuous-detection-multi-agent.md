# AIOps 持续发现与多 Agent 协作 Spec

状态：`DONE_APPLICATION_BOUNDARY`
日期：2026-08-20
范围：Prometheus/Alertmanager 持续发现、告警去重聚合、协作诊断、受控修复、独立验证、MTTD/MTTR

## 1. 目标

将当前“用户主动发起智能排查”的能力升级为单业务域 AIOps MVP：监控系统持续发现异常，系统自动沉淀事件并完成证据调查，只允许从白名单生成修复计划，经 ADMIN 审批后执行，随后使用独立健康信号验证并进入 `RESOLVED` 或 `ESCALATED`。

```text
业务指标 -> Prometheus Rule -> Alertmanager Webhook
        -> 验签/落库 -> fingerprint 去重与 groupKey 聚合
        -> RabbitMQ 异步调查 -> 多 Agent 并行取证
        -> Supervisor 汇总根因与置信度
        -> Policy Agent 选择白名单 Runbook
        -> ADMIN 审批 -> 执行 -> Verification Agent
        -> RESOLVED / ESCALATED -> MTTD/MTTR
```

## 2. 架构决策

### 2.1 本期不直接引入新的多 Agent 运行时

项目当前使用 Spring AI 1.0.3，已经具备 Graph/DAG、Durable Runtime、Checkpoint、Harness、Trace 和 Human-in-the-loop 审批。Spring AI Alibaba Agent Framework 1.1.x 虽提供 `RoutingAgent`、`ParallelAgent`、`Supervisor` 和 Graph Runtime，但直接引入会同时触发 Spring AI 版本升级、状态模型迁移和回归范围扩张。

本期采用框架通用模式，而不绑定新依赖：

- `AiopsCollaborationEngine`：协作引擎 SPI。
- `BuiltinSupervisorCollaborationEngine`：默认实现，复用当前 Spring Boot、Graph/DAG、Harness 与 PostgreSQL Trace。
- `AiopsSpecialistAgent`：指标、日志、变更三个只读专家接口。
- `AiopsDiagnosisSupervisor`：合并证据、生成根因假设与置信度。
- `AiopsRemediationPolicy`：确定性映射到白名单 Runbook，LLM 无权生成命令。
- `AiopsVerificationAgent`：执行后重新读取健康信号，不复用执行返回值作为成功依据。

后续可在独立升级分支实现 `SpringAiAlibabaCollaborationEngine`，但不得改变本 Spec 的领域接口、状态机和验收用例。

### 2.2 协作模式

```text
Alert Context
   |-- Metrics Specialist ----|
   |-- Log Specialist --------|--> Supervisor --> Diagnosis
   |-- Change Specialist -----|                      |
                                                     v
                                            Remediation Policy
                                                     |
                                              Controlled Plan
                                                     |
                                              Verification Agent
```

专家节点允许并行、超时和降级；Supervisor 必须标记缺失证据，不得把空结果描述为已验证事实。

## 3. 功能需求

### AIOPS-CD-FR-001 Alertmanager Webhook

- 新增 `POST /api/ai/aiops/alerts/alertmanager`。
- 使用 `X-AIOPS-Webhook-Token` 与服务端配置做常量时间比较。
- 服务端未配置 Token 时拒绝接收，不允许空密钥降级。
- Webhook 只完成校验、标准化、落库和消息发布；调查异步执行。
- 保存 receiver、groupKey、commonLabels、status、startsAt/endsAt、原始 payload hash 和 traceId。

### AIOPS-CD-FR-002 fingerprint 去重与聚合

- 单条告警 fingerprint 由可信字段规范化后计算：`source + alertname + service/job + instance + namespace + cluster`。
- 相同 fingerprint 的重复 firing 只更新 `lastSeenAt` 和 `occurrenceCount`。
- Alertmanager `groupKey` 作为 `correlationKey`，相关告警共享该字段，但仍保留各自 fingerprint。
- 重复 delivery 通过 payload hash 幂等，不重复发布调查消息。
- resolved 通知写入 `sourceEndedAt`；未执行修复的 OPEN/DIAGNOSED 事件可标记 `SOURCE_RECOVERED`。

### AIOPS-CD-FR-003 异步调查

- 使用 RabbitMQ 独立 exchange/queue/routing key。
- 消费采用手动确认、有限重试和 DLQ；重复消息不得重复创建计划。
- 消费失败保存 delivery 状态和错误原因。
- RabbitMQ 不可用时 Webhook 返回明确失败，不伪装为已受理。

### AIOPS-CD-FR-004 多 Agent 证据调查

- Metrics Specialist：根据后端模板和告警标签生成 PromQL，禁止接收模型生成的任意 PromQL。
- Log Specialist：按 traceId/service/time window 查询；日志源不可用时返回 `DEGRADED`。
- Change Specialist：查询同服务告警前 30 分钟内的部署、配置、索引和受控操作变更。
- Supervisor 输出：`rootCauseHypotheses`、`evidence`、`missingEvidence`、`confidence`、`recommendedActionCode`。
- 证据项必须带 `source/tool/target/summary/observedAt`。

### AIOPS-CD-FR-005 白名单修复计划

- Policy Agent 只能选择后端 Runbook Catalog 中存在的 actionCode。
- 初始支持：
  - `REPAIR_KNOWLEDGE_DOC`
  - `REPAIR_KNOWLEDGE_BATCH`
  - `ROLLBACK_KNOWLEDGE_REVISION`
  - `RESET_DEMO_SERVICE_FAULT`
- 自动生成的计划必须关联 `sourceEventId`、traceId、fingerprint 和 diagnosis hash。
- 不自动审批；真实执行仍要求 ADMIN。

### AIOPS-CD-FR-006 测试服务与受控修复

- 提供默认关闭的 demo fault profile，仅用于本地/验收环境。
- 允许的 service 必须来自配置白名单，默认仅 `marketplace-demo-worker`。
- `RESET_DEMO_SERVICE_FAULT` 只能清除该服务的受控故障状态，不允许任意 Shell、容器名或 URL。
- 暴露 Micrometer 指标 `aiops_demo_service_health` 与 `aiops_demo_service_error_ratio` 供 Prometheus 采集。

### AIOPS-CD-FR-007 独立健康验证

- 修复完成后进入 `VERIFYING`。
- Verification Agent 重新读取 demo health、错误率和目标 Runbook 的领域校验结果。
- 验证通过：operation 与 source event 均进入 `RESOLVED`。
- 验证失败/超时：operation 与 source event 进入 `ESCALATED`，`manualRequired=true`。

### AIOPS-CD-FR-008 MTTD/MTTR

- `MTTD = first_seen_at - source_started_at`。
- `automation_repair_ms = resolved_at - first_seen_at`。
- `full_mttr_ms = resolved_at - source_started_at`。
- 指标输出 count、average、P50、P95；缺少 sourceStartedAt 的旧事件不进入 MTTD/full MTTR 样本。

## 4. 数据模型

`aiops_event` 增加：

| 字段 | 类型 | 说明 |
|---|---|---|
| fingerprint | VARCHAR(64) | 规范化告警指纹 |
| correlation_key | VARCHAR(160) | Alertmanager groupKey 摘要 |
| occurrence_count | INTEGER | 重复告警次数 |
| source_started_at | TIMESTAMP | 监控侧首次异常时间 |
| source_ended_at | TIMESTAMP | 监控侧恢复时间 |
| diagnosis_status | VARCHAR(32) | PENDING/RUNNING/COMPLETED/DEGRADED/FAILED |
| diagnosis_json | JSONB | 结构化证据和根因结论 |
| operation_id | BIGINT | 自动生成的受控操作计划 ID |

新增 `aiops_alert_delivery`：保存 deliveryKey、payloadHash、groupKey、status、alertCount、processingStatus、errorMsg、payloadJson 和时间字段。

新增 `aiops_change_event`：保存 service、environment、changeType、version、traceId、summary、metadataJson 和 occurredAt。

## 5. 配置

```yaml
ai:
  aiops:
    alertmanager:
      enabled: false
      webhook-token: ${AIOPS_ALERT_WEBHOOK_TOKEN:}
      rabbit-enabled: true
      max-alerts-per-delivery: 100
    collaboration:
      provider: builtin
      specialist-timeout-ms: 3500
      max-parallelism: 3
    demo:
      enabled: false
      allowed-services:
        - marketplace-demo-worker
```

生产默认关闭 webhook 与 demo profile；只有显式配置 token 后才允许接收。

## 6. API

```http
POST /api/ai/aiops/alerts/alertmanager
POST /api/ai/aiops/changes                    INTERNAL
GET  /api/ai/aiops/alerts/deliveries/{id}     INTERNAL
POST /api/ai/aiops/demo/faults                ADMIN + demo enabled
GET  /api/ai/aiops/demo/services/{service}    INTERNAL + demo enabled
GET  /api/ai/aiops/metrics/operations         INTERNAL
```

Webhook 响应至少包含：`deliveryId`、`duplicate`、`acceptedAlerts`、`eventIds`、`traceId`。

## 7. 安全规则

- Webhook secret 不写入日志、响应、Trace 或报告。
- 所有标签和 annotations 视为不可信输入；限制长度、数量并做 Prompt Injection 标记清洗。
- 告警 annotations 只能作为“外部观察”，不能成为 Agent 指令。
- 自动生成计划不代表批准；SYSTEM/INTERNAL 无真实执行权限。
- Runbook 参数必须由服务端 schema 重建，不能透传 annotations。
- demo fault endpoint 默认关闭，生产环境不得启用。

## 8. 验收标准

- `AIOPS-CD-AC-001`：合法 firing webhook 在 500 ms 内受理并生成事件。
- `AIOPS-CD-AC-002`：错误或缺失 Token 返回 401/403，数据库无新增 delivery/event。
- `AIOPS-CD-AC-003`：同 fingerprint 重复 5 次只有一个事件，occurrenceCount=5。
- `AIOPS-CD-AC-004`：同 payload 重放只发布一次调查任务。
- `AIOPS-CD-AC-005`：同 groupKey 的不同 fingerprint 共享 correlationKey。
- `AIOPS-CD-AC-006`：指标、日志、变更专家并行完成，缺失源被标记为 DEGRADED。
- `AIOPS-CD-AC-007`：Supervisor 结论中的每条事实可追溯到 evidence。
- `AIOPS-CD-AC-008`：只能生成目录内 actionCode，annotations 不能注入任意工具或参数。
- `AIOPS-CD-AC-009`：未审批计划无法执行，INTERNAL/USER 均被拒绝。
- `AIOPS-CD-AC-010`：demo 修复成功后独立验证通过，source event 为 RESOLVED。
- `AIOPS-CD-AC-011`：验证失败时 source event 为 ESCALATED 且转人工字段完整。
- `AIOPS-CD-AC-012`：resolved webhook 能关闭未进入执行阶段的事件。
- `AIOPS-CD-AC-013`：MTTD、automation repair 和 full MTTR 与离线时间轴误差不超过 1 ms。
- `AIOPS-CD-AC-014`：RabbitMQ 重复投递不产生重复计划。
- `AIOPS-CD-AC-015`：全量 Maven 测试、原 16 条 remediation loop 和新评测均通过。

## 9. 评测交付物

```text
eval/datasets/aiops_continuous_detection_eval_dataset.json
tools/aiops_continuous_detection_eval.py
eval/reports/aiops_continuous_detection_eval_report.md
deploy/observability/prometheus/aiops-alert-rules.yml
deploy/observability/alertmanager/alertmanager.yml
docs/AIOPS_CONTINUOUS_DETECTION_TEST.md
```

评测至少 20 条，覆盖验签、重复、聚合、异步、证据、注入防护、权限、成功修复、失败转人工、resolved 通知和指标准确性。

## 10. 完成边界

本 Spec 完成代表“面向当前二手交易平台的单业务域 AIOps MVP”，不代表通用 AIOps 平台。本期不包含 Kubernetes 多集群、任意命令执行、服务拓扑自动发现、告警静默管理、PagerDuty/工单集成和多租户。

## 11. 实施与验收记录

- 已实现 Alertmanager webhook 验签、payload 幂等、fingerprint 去重、groupKey 关联、RabbitMQ 异步调查和 delivery 状态追踪。
- 已实现 Metrics/Log/Change 三类只读专家并行取证、Supervisor 汇总、确定性白名单 Policy 和独立 Verification。
- 已实现 `RESET_DEMO_SERVICE_FAULT` 受控演示 Runbook，真实执行仅允许 ADMIN 审批，USER/INTERNAL 均被拒绝。
- 已修复真实 Alertmanager groupKey 与 payload hash 拼接导致 deliveryKey 超长的问题，改为固定长度 SHA-256 派生键。
- 已修复 delivery INSERT 参数与列数量不一致，以及 Alertmanager UTC 时间写入本地无时区字段导致 MTTD 偏移 8 小时的问题。
- `eval/reports/aiops_continuous_detection_eval_report.md`：27/27 通过，通过率 100%，P95 518.1 ms。
- `eval/reports/aiops_remediation_loop_eval_report.md`：原修复闭环 16/16 通过，覆盖执行失败转人工、幂等重放与 MTTR。
- `mvn test` 全量测试通过；Prometheus/Alertmanager 配置文件完成 YAML 静态校验。

本轮真实 E2E 从 Alertmanager webhook 应用边界开始；生产环境中的 Prometheus rule 加载、Alertmanager 高可用部署和真实告警风暴压测仍属于部署验收，不在本 Spec 的代码完成声明内。
