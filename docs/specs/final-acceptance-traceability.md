# 项目最终验收追踪表

更新时间：2026-08-01

状态定义：`DONE` 表示实现、自动化评测和人工验收均完成；`IMPLEMENTED_PENDING_EVAL` 表示代码已实现但在线验收证据不足；`PLANNED` 表示仅有规格。

## A. 智能排查

| ID | 验收项 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| ROUTE-AC-001 | 单工具问题走 DIRECT | `AgentInvestigationServiceImpl` | 待路由在线回归 | IMPLEMENTED_PENDING_EVAL |
| ROUTE-AC-002 | 依赖 Observation 的问题走 REACT | 同上 | 待路由在线回归 | IMPLEMENTED_PENDING_EVAL |
| ROUTE-AC-003 | 独立多工具问题走 DAG | 同上 | 待路由在线回归 | IMPLEMENTED_PENDING_EVAL |
| ROUTE-AC-004 | USER 无工具访问权限 | `ToolAccessGuard`、前端路由 | 待三角色回归 | IMPLEMENTED_PENDING_EVAL |
| ROUTE-AC-005 | traceId 可还原路由和工具步骤 | Agent Trace PG 表 | 既有 Agent Eval | IMPLEMENTED_PENDING_EVAL |

## A2. Hybrid Planner 与有限 Replan

| ID | 验收项 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| PLANNER-AC-001 | 明确意图走规则快路径 | `HybridAgentPlanner` | `eval/reports/hybrid_planner_eval_report.md`，8/8，2026-08-01 | DONE |
| PLANNER-AC-002 | 工具白名单与敏感参数由服务端校验 | `AgentPlanValidator` | `HybridAgentPlannerTest` + 同报告工具断言 | DONE |
| PLANNER-AC-003 | USER 内部工具仅产生 DENIED Trace | Planner + ReAct/Graph executor | 同报告，PLANNER-002/006 | DONE |
| PLANNER-AC-004 | Replan 最多两轮且受 maxSteps 限制 | `HybridAgentPlanner.replan` | `HybridAgentPlannerTest`，低证据追加、上限与拒绝后不扩权 | DONE |
| PLANNER-AC-005 | Graph 追加节点唯一且只生成一次 Final | `GraphReactAgentServiceImpl` | `GraphReactAgentReplanTest` + 在线 DAG 断言 | DONE |
| PLANNER-AC-006 | LLM 失败回退规则计划 | `HybridAgentPlanner.plan` | `HybridAgentPlannerTest.shouldFallbackToRulePlanWhenStructuredPlannerFails` | DONE |

## B. 受控 Repair 与 AIOps

| ID | 验收项 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| APPROVAL-AC-001 | 未审批不能执行，ADMIN 才能审批 | `ControlledOperationController/ServiceImpl` | `eval/reports/controlled_operation_eval_report.md`，CONTROL-001/002/003/004/007/008/011/012，2026-07-27 | DONE |
| APPROVAL-AC-002 | 执行使用审批快照 | `ControlledOperationServiceImpl` | 同报告，CONTROL-005/006，2026-07-27 | DONE |
| APPROVAL-AC-003 | Repair 后三存储验证通过才关闭 | `AiopsRunbookServiceImpl` | 同报告，CONTROL-005，2026-07-27 | DONE |
| APPROVAL-AC-004 | 失败进入 ESCALATED 并转人工 | `AiopsEventServiceImpl` | `eval/reports/aiops_remediation_loop_eval_report.md`，AIOPS-LOOP-011/012，2026-07-27 | DONE |
| APPROVAL-AC-005 | 幂等键避免重复副作用 | AIOps action 唯一约束 | 同报告，AIOPS-LOOP-010，2026-07-27 | DONE |
| SECURITY-01 | 旧 Repair/Runbook 真执行入口返回 409 | `KnowledgeDocController`、`AiopsRunbookController` | 同报告，CONTROL-009/010，2026-07-27 | DONE |

## C. Durable Agent Runtime

| ID | 验收项 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| RUNTIME-AC-001 | 恢复跳过已成功工具节点 | `AgentRuntimeServiceImpl`、`GraphExecutionOptions` | `eval/reports/durable_runtime_eval_report.md`，RUNTIME-009/010/011，2026-07-27 | DONE |
| RUNTIME-AC-002 | 并发恢复只有一个 lease 获胜 | Runtime 条件更新 | 同报告，RUNTIME-011 | DONE |
| RUNTIME-AC-003 | USER/INTERNAL/ADMIN 权限边界 | `AgentRuntimeController` | 同报告，RUNTIME-005/006/007/008 | DONE |
| RUNTIME-AC-004 | 快照重放不调用工具，实时重放调用只读工具 | Runtime replay | 同报告，RUNTIME-012 至 015 | DONE |
| RUNTIME-AC-005 | Run/Node/Checkpoint 可关联 | Runtime PG 表与 API | 同报告，RUNTIME-001 至 004 | DONE |
| FE-AC-005 | 页面按钮与状态、角色一致 | `AgentRunListView.vue`、`AgentRunDetailView.vue` | 前端构建通过，待人工验收 | IMPLEMENTED_PENDING_EVAL |

## D. 演示交付

| ID | 验收项 | 证据 | 状态 |
| --- | --- | --- | --- |
| DEMO-AC-001 | 五分钟内覆盖 RAG、知识缺口、智能排查、Runtime、受控修复 | `docs/FIVE_MINUTE_DEMO_CHECKLIST.md` | IMPLEMENTED_PENDING_EVAL |
| DEMO-AC-002 | 外部依赖失败时明确展示降级，不伪造结果 | 演示失败预案 | IMPLEMENTED_PENDING_EVAL |

Demo Center、Seed/Reset UI 已由用户明确移出本期范围，不作为阻塞项。

## E. 构建与交付

| ID | 验收项 | 结果 | 证据 | 日期 |
| --- | --- | --- | --- | --- |
| BUILD-01 | Maven 打包 | PASS | `.\mvnw.cmd -DskipTests package` | 2026-07-12 |
| BUILD-02 | Vue 类型检查和生产构建 | PASS | Node 22.13.0，`npm run build` | 2026-07-12 |
| BUILD-03 | 新增 JSON/Python 静态检查 | PASS | `json.tool`、`py_compile` | 2026-07-12 |
| DOC-01 | Spec 索引、Runtime Spec、演示清单一致 | PASS | 文档交叉检查 | 2026-07-12 |

## F. 待最终签字

1. 启动后端时开启 Runtime 故障注入，仅用于评测环境。
2. 运行 `controlled_operation_eval.py` 和 `durable_runtime_eval.py`，保存全通过报告。
3. 用 USER、INTERNAL、ADMIN 逐一检查导航和直接 URL。
4. 按五分钟演示清单完整走一遍，并记录 traceId。

当前结论：`代码收口完成，在线验收待执行`。

## G. 企业级RAG入库与Query Rewrite（新增规划）

| ID | 验收项 | 计划实现 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| CHUNK-AC-001 | MD/PDF/DOCX格式路由与有效正文解析 | DocumentParserRouter | `ingestion_chunk_eval.py` | IMPLEMENTED_PENDING_EVAL |
| CHUNK-AC-002 | Gold段落完整覆盖且无异常重复 | ChunkQualityValidator | 同上 | IMPLEMENTED_PENDING_EVAL |
| CHUNK-AC-003 | 真实Token计数及最大长度约束 | EnterpriseChunkingPipeline | 同上 | IMPLEMENTED_PENDING_EVAL |
| CHUNK-AC-004 | LLM切片失败确定性降级 | SemanticBoundarySplitter | 故障注入用例 | IMPLEMENTED_PENDING_EVAL |
| CHUNK-AC-005 | 新版入库失败不影响旧ACTIVE版本 | VersionedIngestionService | 回滚用例 | IMPLEMENTED_PENDING_EVAL |
| CHUNK-AC-006 | 新版本三存储版本和chunkId一致 | 一致性服务 | storage eval | IMPLEMENTED_PENDING_EVAL |
| REWRITE-AC-001 | 简单问题走无LLM快速路径 | RewriteTrigger | `eval/reports/query_rewrite_eval_report.md`，QR-001/002/006/009/012，2026-08-01 | DONE |
| REWRITE-AC-002 | 多意图问题动态生成受限子查询 | QueryRewriteService | 同报告，改写用例分支数均为2且意图覆盖通过 | DONE |
| REWRITE-AC-003 | 实体和否定条件100%保留 | RewriteFaithfulnessValidator | 同报告，entityRetentionRate=100%，negationRetentionRate=100% | DONE |
| REWRITE-AC-004 | Rewrite异常降级为Original Query | QueryRewriteService | 故障注入用例 | IMPLEMENTED_PENDING_EVAL |
| REWRITE-AC-005/006 | Recall@5不回退且困难子集命中提升 | Weighted RRF | 同报告，Recall@5 83.33% -> 100%，MRR 0.653 -> 0.743，noHitRecovery=2 | DONE |
| REWRITE-AC-007 | 非知识工具不进入Rewrite | 工具路由边界 | Agent回归 | IMPLEMENTED_PENDING_EVAL |
| REWRITE-AC-008 | Rewrite新增延迟受预算控制 | 超时与指标 | 同报告，P95=7615.9ms，低于当前15000ms超时预算；超时自动降级仍待故障注入 | PARTIAL |

## H. AIOps 修复闭环

| ID | 验收项 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| AIOPS-AC-001 | 诊断、审批、修复、验证、关闭形成正常闭环 | `AiopsEventServiceImpl`、`AiopsRunbookServiceImpl` | `eval/reports/aiops_remediation_loop_eval_report.md`，16/16，2026-07-27 | DONE |
| AIOPS-AC-002 | USER/INTERNAL 无权审批和真实执行 | AIOps Controller、`ToolAccessGuard` | 同报告，AIOPS-LOOP-002/003/005/006/008 | DONE |
| AIOPS-AC-003 | 验证失败转 ESCALATED 并要求人工处理 | AIOps Event/Action | 同报告，AIOPS-LOOP-011/012 | DONE |
| AIOPS-AC-004 | 相同幂等键只产生一次副作用 | AIOps Action 唯一约束 | 同报告，AIOPS-LOOP-010 | DONE |
| AIOPS-AC-005 | MTTR 平均值、P50、P95 准确 | AIOps Metrics | 同报告，AIOPS-LOOP-014/015/016 | DONE |

## I. 发布助手反馈闭环

| ID | 验收项 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| PUB-AC-001 | assistId/traceId 与输入输出证据可追踪 | `PublishAssistFeedbackServiceImpl` | `eval/reports/publish_feedback_eval_report.md`，PUB-001/002/003，2026-07-27 | DONE |
| PUB-AC-002 | 字段级采纳、upsert 和商品关联正确 | 同上 | 同报告，PUB-005 至 PUB-011 | DONE |
| PUB-AC-003 | 非所有者访问和反馈被拒绝 | Controller、`ToolAccessGuard` | 同报告，PUB-004/012/017 | DONE |
| PUB-AC-004 | ADMIN 最终风险结果正确回流 | `PublishAssistFeedbackServiceImpl` | 同报告，PUB-013/014/015/019 | DONE |
| PUB-AC-005 | 采纳率和风险指标与离线样本一致 | Publish Assist Metrics | 同报告，PUB-016/018/020 | DONE |

说明：本次 `PUB-001` 在模型额度不可用环境下使用静态降级文案。上述 DONE 仅覆盖反馈闭环 Spec，不代表在线 LLM 文案生成质量通过。

## I2. 发布助手字段保真与风险判断

| ID | 验收项 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| PUBLISH-BIZ-AC-001 | 型号、容量、电池、配件、维修、发票和来源事实保真 | `OptimizeStepImpl`、发布 Prompt | `eval/reports/publish_business_eval_report.md`，12/12，事实保真率100%，2026-08-01 | DONE |
| PUBLISH-BIZ-AC-002 | 硬风险确定性阻断且 Prompt Injection 不绕过 | `RiskAssessmentServiceImpl`、`DecisionStepImpl` | 同报告，硬风险阻断召回率100% | DONE |
| PUBLISH-BIZ-AC-003 | 否定句和规则引用不误杀安全发布 | `RiskAssessmentServiceImpl` | 同报告，安全样例误杀率0% | DONE |
| PUBLISH-BIZ-AC-004 | 同一风险规则不重复计分 | 风险证据去重 | 同报告，规则去重率100% | DONE |

性能基线：P95=`18972.7ms`。该指标不阻塞本次业务正确性签字，后续从内部固定查询关闭 Rewrite、并行检索和模型调用次数三个方向治理。

## J. 知识缺口治理

| ID | 验收项 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| GAP-AC-001 | 低置信度拒答沉淀 OPEN 缺口并脱敏 | `KnowledgeGapServiceImpl` | `eval/reports/knowledge_gap_eval_report.md`，GAP-001~004/010/012~015/017/026，2026-08-01 | DONE |
| GAP-AC-002 | 同义重复问题聚合 occurrenceCount | 同上 | 同报告，GAP-005/006/011/022/026 | DONE |
| GAP-AC-003 | 缺知识与缺字段正确区分 | 同上 | 同报告，GAP-007~009/016/017 | DONE |
| GAP-AC-004 | 关联已入库文档后回归成功才关闭 | Knowledge Gap Regression | 同报告，GAP-018~022 | DONE |
| GAP-AC-005 | 回归失败保持未关闭并记录证据 | Knowledge Gap Regression | 同报告，GAP-023~025 | DONE |

## K. Trace 与 Eval Dashboard

| ID | 验收项 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| TRACE-AC-001 | Agent Trace 可还原 Run 和步骤 | Agent Trace PG 表与 API | Agent/Runtime 回归 | IMPLEMENTED_PENDING_EVAL |
| TRACE-AC-002 | 失败和降级原因正确落库 | Agent Trace Persistence | 故障用例 | IMPLEMENTED_PENDING_EVAL |
| TRACE-AC-003 | 评测运行和 total/passed/P95 可在 Dashboard 查询 | Eval Metrics PG 表与 API | 全部在线评测 | IMPLEMENTED_PENDING_EVAL |
| TRACE-AC-004 | 发布、缺口、AIOps 记录可反查 traceId/evalRunId | 业务表关联字段 | 对应闭环评测 | IMPLEMENTED_PENDING_EVAL |
| TRACE-AC-005 | USER 不能查看内部 Trace | Trace Controller、`ToolAccessGuard` | 三角色回归 | IMPLEMENTED_PENDING_EVAL |

## K2. Token 治理 MVP

| ID | 验收项 | 实现位置 | 自动化证据 | 状态 |
| --- | --- | --- | --- | --- |
| TOKEN-AC-001 | 最终 Prompt 不超过场景输入预算 | `TokenGovernanceServiceImpl` | `eval/reports/token_governance_eval_report.md`，8/8，2026-08-01 | DONE |
| TOKEN-AC-002 | 优先裁剪低优先级上下文并保留问题和证据 | 同上 | 同报告，TOKEN-002~008，trimmedTokens=196353 | DONE |
| TOKEN-AC-003 | 真实模型 Usage 或估算值正确落库 | `ChatGenerationServiceImpl`、`ai_token_usage` | 待可用模型实际链路复核 | IMPLEMENTED_PENDING_EVAL |
| TOKEN-AC-004 | RAG DONE 返回摘要且可按 traceId 反查 | `RagChatServiceImpl`、Usage API | 待实际 RAG 调用复核 | IMPLEMENTED_PENDING_EVAL |
| TOKEN-AC-005 | Prometheus 暴露 `ai_token_*` 指标 | `TokenUsageServiceImpl` | 待 `/actuator/prometheus` 复核 | IMPLEMENTED_PENDING_EVAL |
| TOKEN-AC-006 | 固定评测集通过且不消耗模型 Token | `token_governance_eval.py` | 同报告，8/8，P95=205.3ms | DONE |

## L. 前端收口验收补充

`FE-AC-005` 已在 C 节关联 Runtime，本节列出其余前端验收项。

| ID | 验收项 | 实现位置 | 人工/E2E证据 | 状态 |
| --- | --- | --- | --- | --- |
| FE-AC-001 | USER/INTERNAL/ADMIN 导航和直接 URL 隔离 | Router、Auth Store、MainLayout | 三角色人工验收 | IMPLEMENTED_PENDING_EVAL |
| FE-AC-002 | DIRECT/REACT/DAG 结构化展示而非原始 JSON | `AgentInvestigationView.vue` | 三策略人工验收 | IMPLEMENTED_PENDING_EVAL |
| FE-AC-003 | 审批参数快照只读不可篡改 | AIOps/Storage 页面 | 审批人工验收 | IMPLEMENTED_PENDING_EVAL |
| FE-AC-004 | 操作按钮由状态和风险等级驱动 | AIOps/Runtime 页面 | 状态矩阵人工验收 | IMPLEMENTED_PENDING_EVAL |
| FE-AC-006 | 降级状态持续可见且不冒充正式通过 | 页面错误/降级状态 | 故障人工验收 | IMPLEMENTED_PENDING_EVAL |
| FE-AC-007 | loading/empty/403/409/500/success 状态完整 | 新增 AI 页面 | 组件/E2E/人工验收 | IMPLEMENTED_PENDING_EVAL |

## M. GitHub 交付

| ID | 验收项 | 实现位置 | 验收证据 | 状态 |
| --- | --- | --- | --- | --- |
| GH-AC-001 | README 可独立说明目标、闭环、权限和指标来源 | 根 README、`docs/benchmarks/README.md` | 2026-08-26 双人只读审查完成 | DONE |
| GH-AC-002 | check-env 能定位依赖故障且不泄密 | `scripts/check-env.ps1`、`tools/demo_preflight.py` | 2026-08-26 本机执行，输出变量/服务状态但不输出值 | DONE |
| GH-AC-003 | Seed 与 smoke-test 可重复执行且幂等 | `scripts/smoke-test.ps1`、`sql/marketplace_demo_seed.sql` | 只读预检已实现，需在完整三角色在线环境复跑 | IMPLEMENTED_PENDING_LIVE_ACCEPTANCE |
| GH-AC-004 | Secret scan 无真实密钥、密码和个人配置 | 公开交付前扫描 | Key/JWT/私钥模式未命中；`application-idea.yml` 仍含本机密码 fallback，发布前需脱敏且保留本地配置 | RISK |
| GH-AC-005 | Maven 与 Vue 构建成功 | Maven、Vue、GitHub Actions | 2026-08-26：JUnit 109（0 failure/error，1 skipped），Vue production build 通过 | DONE |
