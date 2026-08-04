# 智能排查统一入口 Spec

状态：`IMPLEMENTED_PENDING_EVAL`

范围：隐藏 ReAct/Graph 技术菜单、统一智能排查入口、后端执行策略路由、角色隔离。

现有基础：ReAct Agent、Graph/DAG Agent、工具注册中心、Tool Execution Harness、Agent Trace、USER/INTERNAL/ADMIN 权限。

前端合同：见 [项目收尾前端实现补充 Spec](./frontend-closure-contract.md) 第2、3、4节。

## 1. 背景与目标

当前前端把 ReAct、Graph 和 AIOps 暴露为并列功能，用户需要理解内部技术实现后才能选择入口。ReAct 和 DAG 实际是执行策略，不应成为业务菜单。

本 Spec 建立统一“智能排查”入口，由后端根据任务依赖、工具数量、风险级别和权限选择直接调用、ReAct 或 DAG，并在响应和 Trace 中记录路由依据。

## 2. 核心流程

```text
INTERNAL/ADMIN 提交排查问题
-> 意图与权限检查
-> 生成执行计划
-> 路由执行策略
   -> DIRECT：单工具确定性查询
   -> REACT：后一步依赖前一步 observation
   -> DAG：多个独立查询可并行
-> Harness 执行工具
-> 汇总证据并生成结论
-> 保存 trace、routeReason 和执行指标
```

## 3. 功能需求

### ROUTE-FR-001 统一业务入口

新增统一接口，前端只展示“智能排查”。原 ReAct/Graph 接口保留为 INTERNAL/ADMIN 调试接口，不进入普通导航。

### ROUTE-FR-002 可解释路由

路由规则优先采用确定性特征：

- 单个明确工具且无需模型二次判断：`DIRECT`。
- 工具存在数据依赖或需要依据 observation 动态决定下一步：`REACT`。
- 至少两个无依赖、只读工具可并行：`DAG`。
- 涉及写操作：只生成受控计划并进入审批，不直接执行。

响应和 Trace 必须记录 `strategy`、`routeReason`、候选工具和最终工具，禁止只记录最终答案。

### ROUTE-FR-003 权限隔离

- USER 不可访问智能排查入口。
- INTERNAL 可调用知识库、日志、Prometheus 和只读数据库工具。
- ADMIN 具备 INTERNAL 能力，并可对受控动作进行审批。
- 模型不能通过输出 mode 或 toolName 提升调用者权限。

### ROUTE-FR-004 兼容与降级

路由失败时默认降级到受步数限制的 ReAct；DAG 中单节点失败时保留成功节点 observation，并根据任务必要性决定降级回答或整体失败。

## 4. API 契约

```http
POST /api/ai/agent/investigate
```

请求示例：

```json
{
  "question": "检查接口 P95 上升是否与数据库或检索服务有关",
  "sessionId": "demo-aiops-001",
  "maxSteps": 6
}
```

响应至少包含：

```json
{
  "traceId": "...",
  "strategy": "DAG",
  "routeReason": "Prometheus 与日志查询无直接依赖，可并行执行",
  "status": "COMPLETED",
  "answer": "...",
  "evidence": [],
  "approvalRequired": false
}
```

## 5. 安全与质量规则

- 不向 USER 暴露内部工具名、参数、日志原文和数据库结构。
- 路由器只决定执行策略，工具权限仍由 `ToolAccessGuard` 在执行前强制校验。
- 不保存模型完整隐式思维链，只保存计划摘要和路由依据。
- 每次运行限制最大步骤、最大并行度、总超时和 Token 预算。

## 6. 验收标准

### ROUTE-AC-001 单工具路由

Given 明确的单项指标查询，When 调用统一入口，Then 使用 DIRECT，且只调用目标工具一次。

### ROUTE-AC-002 动态依赖路由

Given 必须先查指标再根据结果查日志的问题，When 调用统一入口，Then 使用 REACT，后续动作能够引用前一步 observation。

### ROUTE-AC-003 并行路由

Given 日志、指标和知识规则可独立查询，When 调用统一入口，Then 使用 DAG，并行节点没有虚假依赖。

### ROUTE-AC-004 权限拒绝

Given USER，When 调用智能排查或原调试接口，Then 返回 403 且无工具执行记录。

### ROUTE-AC-005 Trace 完整

Given 任意成功或失败运行，When 查询 traceId，Then 可看到路由策略、节点/步骤、工具 observation、耗时和最终状态。

## 7. 评测映射

新增 `eval/datasets/agent_routing_eval_dataset.json` 和 `tools/agent_routing_eval.py`，至少 20 条，输出 Strategy Accuracy、Tool Selection Accuracy、Parallelizable Recall、Permission Rejection Accuracy、Trace Completeness 和 P95。
