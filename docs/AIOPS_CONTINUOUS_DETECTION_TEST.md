# AIOps 持续发现与受控修复验收

## 1. 启动前配置

在 IDEA Run Configuration 的 Environment variables 中设置：

```text
AIOPS_ALERTMANAGER_ENABLED=true
AIOPS_ALERT_WEBHOOK_TOKEN=replace-with-a-random-local-secret
AIOPS_DEMO_ENABLED=true
AIOPS_DEMO_SERVICE=marketplace-demo-worker
```

同时保证 PostgreSQL、Redis、RabbitMQ、Prometheus 可访问。应用启动时会通过
`AiObservabilityPgInitializer` 幂等创建新增 PostgreSQL 表和字段。

## 2. Prometheus 与 Alertmanager

1. 将 `deploy/observability/prometheus/aiops-alert-rules.yml` 挂载到 Prometheus，并在
   `prometheus.yml` 的 `rule_files` 中引用它。
2. Prometheus 配置 `alerting.alertmanagers` 指向 Alertmanager。
3. 将 `deploy/observability/alertmanager/alertmanager.yml` 挂载到 Alertmanager。
4. 按实际网络修改 webhook URL。VMware Linux 容器访问 Windows IDEA 时，通常使用
   Windows 的 VMnet8 地址；Docker Desktop 可尝试 `host.docker.internal`。
5. 在 Alertmanager 容器内创建只读文件
   `/etc/alertmanager/secrets/aiops_webhook_token`，内容必须与
   `AIOPS_ALERT_WEBHOOK_TOKEN` 相同。不要把该文件提交到 Git。

原生 Alertmanager 使用 `Authorization: Bearer`；Postman 也可使用
`X-AIOPS-Webhook-Token`。两种方式最终都由业务层做常量时间密钥校验。

## 3. 自动化验收

```powershell
python tools/aiops_continuous_detection_eval.py `
  --base-url http://localhost:8080 `
  --webhook-token "replace-with-a-random-local-secret" `
  --user-bearer-token "$env:USER_TOKEN" `
  --internal-bearer-token "$env:INTERNAL_TOKEN" `
  --admin-bearer-token "$env:ADMIN_TOKEN"
```

报告写入：

```text
eval/reports/aiops_continuous_detection_eval_report.md
```

任一请求超时、鉴权绕过、重复事件/计划、无证据生成计划、未审批执行成功、修复后未独立
验证，均必须记为 `RISK` 或 `BLOCKED`，不能按通过处理。

## 4. 五分钟手工演示

1. ADMIN 调用 `POST /api/ai/aiops/demo/faults` 注入受控故障。
2. 在 `/actuator/prometheus` 确认 `aiops_demo_service_health 0`。
3. 等待 Prometheus 规则进入 firing，查看 Alertmanager delivery。
4. INTERNAL 查询 `GET /api/ai/aiops/events?source=alertmanager` 或事件详情，确认专家证据、
   diagnosis、missingEvidence 和 operationId。
5. ADMIN 审批并执行 operation；INTERNAL/USER 执行必须被拒绝。
6. 查询 demo 状态、源事件和 MTTR 指标，确认独立验证后为 `RESOLVED`；验证失败必须为
   `ESCALATED` 且 `manualRequired=true`。
