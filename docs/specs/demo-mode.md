# 面试演示模式 Spec

状态：`SCOPE_REDUCED`

范围：五分钟演示清单、外部依赖健康检查和可解释降级。Demo Center、Seed/Reset UI 已移出本期范围。

现有基础：Docker 中间件、Spring Profile、Vue 前端、评测数据集、知识入库/Repair、Trace 与 Eval Dashboard。

前端合同：见 [项目收尾前端实现补充 Spec](./frontend-closure-contract.md) 第2、3、7节。

## 1. 背景与目标

项目依赖模型、Milvus、ES、PostgreSQL、Redis、RabbitMQ 和 Prometheus。面试现场若任一依赖异常，完整能力难以稳定展示。本 Spec 提供可重复、可清理、可验证的演示环境，但不得用伪造结果冒充真实模型或真实工具调用。

目标是在全新环境中按文档启动后，10 分钟内准备完成，并在 5 分钟内演示 RAG、知识缺口和 AIOps 三条链路。

## 2. 功能需求

### DEMO-FR-001 Demo Profile

新增 `application-demo.yml`，所有地址、账号和密钥只从环境变量读取。默认开启详细健康检查、固定演示数据命名空间和较短超时，不包含真实密钥。

### DEMO-FR-002 固定身份与数据

初始化 USER、INTERNAL、ADMIN 演示账号，固定知识文档、商品、知识缺口、AIOps 事件和可验证故障。初始化脚本必须幂等，并提供清理命令。

### DEMO-FR-003 依赖预检

启动前检查 MySQL/PostgreSQL、Redis、RabbitMQ、ES、Milvus 和 Prometheus。输出 READY、DEGRADED 或 BLOCKED，以及具体依赖和修复提示。

### DEMO-FR-004 可解释降级

模型不可用时允许启用显式 demo fallback，但响应和 UI 必须展示 `demoFallback=true`，Trace 中记录原因。检索、权限、审批、工具调用和持久化结果不得伪造。

### DEMO-FR-005 固定演示旅程

演示脚本覆盖：

1. 商品风险问题命中 RAG 证据并回答。
2. 未知问题拒答并沉淀知识缺口。
3. 补充文档、异步入库、三存储验证和回归成功。
4. INTERNAL 发起智能排查并看到工具证据。
5. ADMIN 审批受控修复，展示验证、Trace 和 MTTR。

## 3. 交付物

```text
application-demo.yml
scripts/demo-start.ps1
scripts/demo-seed.ps1
scripts/demo-reset.ps1
docs/DEMO.md
```

## 4. 验收标准

### DEMO-AC-001 一键启动

Given 已安装 Docker、JDK 和 Node，When 执行启动脚本，Then 所有必需服务通过健康检查并输出前后端地址。

### DEMO-AC-002 重复初始化

Given 已初始化一次，When 再次执行 seed，Then 不产生重复文档、商品、账号和事件。

### DEMO-AC-003 可清理

Given 已完成演示，When 执行 reset，Then 只清理 demo 命名空间数据，不影响普通数据。

### DEMO-AC-004 降级透明

Given LLM 不可用，When 启用 fallback，Then UI、响应和 Trace 都明确标识降级，不能计入真实模型评测。

### DEMO-AC-005 演示稳定性

Given 依赖健康，When 连续执行固定旅程 3 次，Then 核心步骤成功率 100%，且每次都能通过 traceId 还原。

## 5. 评测映射

新增 `tools/demo_smoke_test.py`，串联固定旅程并输出 Demo Readiness、Journey Pass Rate、Dependency Health、Trace Coverage 和总耗时。演示 fallback 结果单独统计，不能与正常评测合并。
