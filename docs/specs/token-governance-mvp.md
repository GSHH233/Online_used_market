# Token 治理 MVP Spec

状态：`IMPLEMENTED_PENDING_EVAL`  
更新时间：2026-08-01

当前证据：`eval/reports/token_governance_eval_report.md`，预算预览 8/8 通过，P95=205.3ms，
累计裁剪 196353 Token；覆盖 `TOKEN-AC-001/002/006`。由于该评测不调用 LLM，
`TOKEN-AC-003/004/005` 的真实 Usage、RAG DONE 回传和 Prometheus 指标仍待实际链路验收。

## 1. 目标

为 RAG Chat、Agent Final Answer 和 Hybrid Planner 提供一层轻量、可追踪、可量化的 Token 治理：

1. 不同场景使用独立输入/输出预算。
2. Prompt 超预算时按业务优先级裁剪，保留本轮问题、RAG 证据和 Observation。
3. 每次模型请求设置最大输出 Token。
4. 优先采集模型供应商返回的 Usage；供应商不返回时用 CL100K 本地估算并明确标记。
5. 用 `traceId + scene` 持久化用量，并提供指标和明细接口。
6. 治理规则可在不调用 LLM 的情况下自动评测。

## 2. 非目标

- 不实现多租户账单、充值、扣费或实时定价。
- 不宣称 CL100K 与 Qwen tokenizer 完全一致。
- 不压缩本轮问题、关键证据来换取更低 Token。
- 不保存模型隐式思维链。

## 3. 场景预算

| 场景 | 输入预算 | 输出预算 | 优先保留 |
| --- | ---: | ---: | --- |
| RAG_CHAT | 6000 | 600 | 回答约束、本轮问题、RAG 证据 |
| AGENT_FINAL | 4000 | 400 | 问题、Observation |
| AGENT_PLANNER | 2000 | 350 | 安全约束、问题、工具白名单 |
| SESSION_SUMMARY | 3000 | 300 | 当前任务、实体、未完成事项 |
| PREFERENCE_EXTRACTION | 1200 | 250 | 抽取规则、本轮明确偏好 |
| QUERY_REWRITE | 1500 | 350 | 改写约束、原始问题、实体与否定词 |
| SEMANTIC_CHUNKING | 3500 | 500 | 标题路径、当前语义窗口 |
| PUBLISH_OPTIMIZE | 2500 | 600 | 用户商品事实、JSON 输出约束 |
| GENERAL | 4000 | 500 | 当前请求 |

所有预算可通过 `AI_TOKEN_*` 环境变量调整。

## 4. 执行流程

```text
业务上下文
-> 按 section 标记 priority / protected
-> TokenGovernanceService 计数与裁剪
-> ChatGenerationService 设置 maxTokens
-> 模型返回 ChatResponse + Usage
-> 无 Usage 时本地估算并标记 usageEstimated=true
-> ai_token_usage + Micrometer
-> metrics/usages API + eval report
```

RAG 裁剪顺序从低到高为：最近会话、L2、Profile、L3、证据、问题/约束。  
Agent 当前使用统一预算裁剪；RAG 已实现 section 级精细裁剪。

## 5. 功能需求

- `TOKEN-FR-001`：按场景读取输入和输出预算。
- `TOKEN-FR-002`：超预算时优先裁剪低优先级非保护 section。
- `TOKEN-FR-003`：模型请求必须携带输出 Token 上限。
- `TOKEN-FR-004`：记录 prompt/completion/total/trimmed Token、模型、重试、耗时和估算标记。
- `TOKEN-FR-005`：RAG WebSocket DONE 返回本次 Token 摘要。
- `TOKEN-FR-006`：INTERNAL 可按 traceId 查询明细与聚合指标。

## 6. API

- `POST /api/ai/token-governance/preview`：预览裁剪结果，不调用模型。
- `GET /api/ai/token-governance/usages?traceId=&limit=50`：查询调用明细。
- `GET /api/ai/token-governance/metrics?recentLimit=100`：查询总量、P95、裁剪量、场景分布和预算遵守率。

以上接口仅允许 INTERNAL/ADMIN。

## 7. 验收标准

- `TOKEN-AC-001`：最终应用 Prompt Token 不超过场景输入预算。
- `TOKEN-AC-002`：低优先级记忆先被裁剪，本轮问题和关键证据仍存在。
- `TOKEN-AC-003`：真实模型调用记录 Usage；缺失 Usage 时记录估算值和估算标记。
- `TOKEN-AC-004`：RAG 返回 Token 摘要，数据库可按 traceId 反查。
- `TOKEN-AC-005`：Prometheus 暴露请求数、输入、输出、裁剪量和总量分布。
- `TOKEN-AC-006`：固定评测集全部通过，且评测过程不消耗模型 Token。

## 8. 验收追踪

| 验收项 | 实现 | 证据 |
| --- | --- | --- |
| TOKEN-AC-001/002 | `TokenGovernanceServiceImpl` | `TokenGovernanceServiceTest`、TOKEN-001~006 |
| TOKEN-AC-003 | `ChatGenerationServiceImpl` | `ai_token_usage.usage_estimated` |
| TOKEN-AC-004 | `RagChatServiceImpl`、`WsChatDoneVO` | RAG DONE payload、usages API |
| TOKEN-AC-005 | `TokenUsageServiceImpl` | `/actuator/prometheus` 的 `ai_token_*` |
| TOKEN-AC-006 | `tools/token_governance_eval.py` | `eval/reports/token_governance_eval_report_*.md` |

## 9. 面试表述

“我没有做复杂计费平台，而是在统一模型出口增加场景预算。RAG 把问题、证据和记忆拆成带优先级的 section，超预算先裁历史上下文，问题和证据最后保留；调用时限制输出 Token，并优先记录供应商 Usage。最终能按 traceId 查看总 Token、裁剪量、P95 和场景分布，固定 8 条用例不依赖模型即可回归。”
