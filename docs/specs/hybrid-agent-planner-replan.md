# Hybrid Planner 与有限动态 Replan Spec

状态：`DONE`  
更新时间：2026-08-01

验收证据：`eval/reports/hybrid_planner_eval_report.md` 在线接口 8/8 通过；
`HybridAgentPlannerTest` 与 `GraphReactAgentReplanTest` 覆盖低证据 Replan、最多两轮、
拒绝后不扩权、Graph 追加节点唯一和 LLM Planner 失败回退。

## 1. 目标

在现有 ReAct、Graph/DAG、Tool Registry、Harness 和 Durable Runtime 上增加共享规划层：

1. 明确意图走规则快速路径，保持延迟和可预测性。
2. INTERNAL 复杂多工具问题可选用 LLM 输出结构化计划。
3. 所有计划必须经过服务端校验，模型不能直接控制敏感工具参数。
4. 工具 Observation 证据不足时允许有限 Replan，不允许无限循环、重复调用或权限升级。
5. Replan 新增的 Graph 节点继续进入 Runtime checkpoint、恢复和幂等链路。

## 2. 非目标

- 不建设通用工作流平台。
- 不允许模型生成任意 SQL、PromQL、文件路径或写操作。
- 不允许 Planner 绕过 USER/INTERNAL/ADMIN 权限、Harness 或受控修复审批。
- 不把故障注入工具暴露给生产 Planner。

## 3. 执行流程

```text
Question + Runtime Context
  -> HybridAgentPlanner
     -> RULE_FAST_PATH
     -> LLM_STRUCTURED（配置开启且 INTERNAL 复杂问题）
     -> RULE_FALLBACK（模型失败或计划非法）
  -> AgentPlanValidator
  -> ReAct 串行执行 / Graph 拓扑执行
  -> Observation
  -> Bounded Replan（最多 2 轮，每轮最多 1 个未调用工具）
  -> Final Answer + Trace + Runtime Checkpoint
```

## 4. 安全约束

- 工具名必须存在于 `AgentToolRegistry`。
- `harness_fault` 永远不能由 Planner 选择。
- USER 模式的内部工具步骤只允许进入显式 `DENIED` 审计路径，不执行工具。
- `mysql_read` 的 SQL 由服务端固定模板生成。
- `prometheus_query` 的 PromQL 由服务端根据问题映射。
- `profile.userId/sessionId`、`log_query.traceId`、`vector_debug.question` 由 Runtime Context 或当前请求覆盖。
- 依赖只能引用此前已接受节点，避免前向依赖和环。
- 工具去重，总调用数受 `maxSteps` 限制；Replan 最多两轮。
- 权限拒绝是终止性 Observation，不触发权限扩张式 Replan。

## 5. 配置

```yaml
ai:
  agent:
    planner:
      llm-enabled: ${AI_AGENT_LLM_PLANNER_ENABLED:false}
      max-replans: ${AI_AGENT_MAX_REPLANS:2}
```

默认关闭 LLM Planner，便于无模型 Token 的本地调试；规则 Planner 和 Observation 驱动 Replan 仍正常工作。开启后仅 INTERNAL 复杂多工具问题进入结构化模型规划。

## 6. 验收标准

- `PLANNER-AC-001`：明确意图走 `RULE_FAST_PATH` 且原有工具选择不回归。
- `PLANNER-AC-002`：非法工具、故障注入工具和敏感参数被服务端拒绝或重写。
- `PLANNER-AC-003`：USER 内部工具请求产生 `DENIED` Trace，工具不实际执行。
- `PLANNER-AC-004`：低证据 Observation 可追加未调用工具，最多 2 轮且不超过 `maxSteps`。
- `PLANNER-AC-005`：Graph Replan 节点 ID 唯一、依赖有效，并写入 Runtime plan/checkpoint。
- `PLANNER-AC-006`：LLM 规划失败时回退规则计划，不阻断 Agent 主链路。

## 7. 验收证据

- 单元测试：`HybridAgentPlannerTest`
- 在线评测：`tools/hybrid_planner_eval.py`
- 数据集：`eval/datasets/hybrid_planner_eval_dataset.json`
- 报告：`eval/reports/hybrid_planner_eval_report.md`

当前 Planner/Graph Replan 单元测试通过，在线 8 条接口用例全部通过。
