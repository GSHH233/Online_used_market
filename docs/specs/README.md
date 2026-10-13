# 项目收口 Spec 索引

更新时间：2026-10-13

本目录采用 Spec 驱动开发：`Spec -> 数据迁移 -> API/Service -> 前端 -> 自动化评测 -> 验收证据`。代码实现但未运行在线评测时，只能标记为 `IMPLEMENTED_PENDING_EVAL`。

| Spec | 状态 | 目标 |
| --- | --- | --- |
| [记忆正确性、质量评测与发布收口](./memory-quality-and-release-hardening.md) | IN_PROGRESS | MEM-01~06 与 Planner 验收已有证据；新 Holdout v6 为 RISK，Judge/成本和发布联调仍待收口 |
| [架构修复复核与实施](./architecture-hardening-review-and-repair.md) | IMPLEMENTED_PENDING_LIVE_ACCEPTANCE | 分页、交易 CAS、上传/WS/监控安全、发布反馈解耦与 Redis L1；本地测试通过，真实联调另验 |
| [Trace 与评测正规化](./traceability-and-eval.md) | IMPLEMENTED | Trace、评测结果与 Dashboard 数据库化 |
| [AIOps 修复闭环](./aiops-remediation-loop.md) | DONE | 修复后验证、失败转人工、MTTR |
| [发布助手反馈闭环](./publish-assist-feedback-loop.md) | DONE | 建议采纳与风险结果回流；在线 LLM 生成质量需在模型可用时单独复测 |
| [知识缺口治理闭环](./knowledge-gap-governance.md) | DONE | 拒答沉淀、知识更新与自动回归 |
| [智能排查统一入口](./agent-investigation-routing.md) | IMPLEMENTED_PENDING_EVAL | 后端选择 DIRECT / REACT / DAG |
| [Hybrid Planner 与有限动态 Replan](./hybrid-agent-planner-replan.md) | DONE | 规则快路径、结构化模型规划、服务端校验和 Observation 驱动补查 |
| [Repair 与自动修复审批](./controlled-repair-approval.md) | DONE | 诊断、审批、执行、验证、转人工 |
| [Durable Agent Runtime MVP](./durable-agent-runtime.md) | DONE | Run/Node/Checkpoint、恢复、幂等与重放 |
| [面试演示模式](./demo-mode.md) | SCOPE_REDUCED | 取消 Demo Center，交付五分钟演示清单 |
| [收尾前端合同](./frontend-closure-contract.md) | IMPLEMENTED_PENDING_EVAL | 页面、路由、字段、角色和状态 |
| [最终验收追踪表](./final-acceptance-traceability.md) | ACTIVE | Spec、代码、评测和人工证据关联 |
| [最终验收运行手册](../SPEC_ACCEPTANCE_RUNBOOK.md) | ACTIVE | 11 个验收批次、命令、人工步骤与签字规则 |
| [GitHub 交付](./github-delivery.md) | PREPARED_PENDING_PUSH | README、文档整理、外部备份、示例配置、CI 与独立发布树验证 |
| [企业级RAG入库切片与Query Rewrite](./enterprise-rag-ingestion-query-rewrite.md) | IMPLEMENTED_PENDING_EVAL | 多格式解析、结构化语义切片、动态多路改写与加权融合 |
| [前端产品叙事收口](./frontend-product-narrative-consolidation.md) | IMPLEMENTED_PENDING_LIVE_ACCEPTANCE | 三条产品主链、角色菜单收口与技术能力下沉 |
| [Token 治理 MVP](./token-governance-mvp.md) | IMPLEMENTED_PENDING_EVAL | 场景预算、优先级裁剪、Usage 审计与量化评测 |

## 追踪规则

状态以各 Spec 的追踪矩阵和报告为准。历史 Planner 全规则路径报告保留，但 2026-09-21 新 live 报告已有真实 LLM/Replan 覆盖；Token 预览不能替代实际 Usage 对照。构建通过和上传成功不代表所有在线评测已通过。

- 功能需求：`<DOMAIN>-FR-xxx`。
- 验收标准：`<DOMAIN>-AC-xxx`。
- 数据集通过 `requirementIds` 关联验收标准。
- Trace、任务、事件和报告使用后端生成的 `traceId` 或 `evalRunId` 串联。
- 测试失败时修复实现或错误规格，不允许只修改问题来规避失败。

## 当前新增评测

- `tools/controlled_operation_eval.py`：受控操作、角色权限、审批快照、旧入口收口。
- `tools/durable_runtime_eval.py`：Run 持久化、checkpoint、故障恢复、节点去重与重放。
- `tools/token_governance_eval.py`：Token 场景预算、优先级裁剪和关键上下文保留。

最终完成标准：后端和前端构建通过，核心在线评测生成全通过报告，人工验收表补齐证据，文档指标与报告一致。
