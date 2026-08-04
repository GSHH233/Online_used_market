# Online Used Marketplace 收尾开发清单

更新时间：2026-07-30  
当前阶段：核心 AI 能力与 AIOps 闭环已完成；正在收口 Token 治理在线评测、产品叙事和公开交付。

## 已完成能力

- [x] Hybrid + RRF 检索，支持 vector-only、keyword-only、hybrid、metadata 过滤和低置信度判定。
- [x] RAG Chat 流式输出、证据引用、拒答、价格忠实度约束和降级。
- [x] ReAct 与 Graph/DAG Agent，支持 USER/INTERNAL/ADMIN 权限边界。
- [x] Hybrid Planner 与有限动态 Replan：规则快路径、可选结构化 LLM 规划、服务端参数校验、最多两轮补查。
- [x] Tool Execution Harness，支持超时、重试、Redis 快照和 fallback。
- [x] L1/L2/L3、Profile、Session Summary 与 Memory Graph 五层评测。
- [x] DB/ES/Milvus 一致性检查、Repair API、Job 状态和 RabbitMQ 异步入库。
- [x] Agent Trace、Eval Dashboard、AIOps Event 和白名单 Runbook 基础能力。
- [x] Token 治理 MVP：场景预算、上下文优先级裁剪、输出上限、Usage 审计与 Prometheus 指标。
- [x] Hybrid Planner 与有限 Replan 验收：8/8 在线接口通过，Replan 与 LLM fallback 单元测试通过。
- [x] 发布助手目标业务验收：12/12，事实保真率与硬风险阻断召回率 100%，安全样例误杀率 0%，规则去重率 100%。

## 收尾阶段

### 1. AIOps 修复闭环

Spec：[aiops-remediation-loop.md](./specs/aiops-remediation-loop.md)

- [x] Spec、状态机、权限和指标公式。
- [x] PostgreSQL 迁移：审批、解决时间、转人工和 Action 幂等字段。
- [x] 审批/拒绝/处置详情/MTTR API。
- [x] Runbook 执行后统一验证及失败转人工。
- [x] `aiops_remediation_loop_eval.py` 与 16 条用例。
- [x] 全部 AIOPS-AC 验收通过。

### 2. 发布助手反馈闭环

Spec：[publish-assist-feedback-loop.md](./specs/publish-assist-feedback-loop.md)

- [x] Spec、指标口径和隐私边界。
- [x] 建议快照、采纳反馈、风险结果数据表。
- [x] publish-assist 返回 assistId/traceId。
- [x] 商品创建关联建议，支持字段级采纳反馈。
- [x] 风险结果回流和聚合指标 API。
- [x] `publish_feedback_eval.py` 与 20 条用例。
- [x] 全部 PUB-AC 验收通过：反馈闭环 20/20，发布业务目标评测 12/12。

### 3. 知识缺口治理闭环

Spec：[knowledge-gap-governance.md](./specs/knowledge-gap-governance.md)

- [x] Spec、去重规则和自动回归判定。
- [x] 缺口、RAG Turn Audit 与回归记录数据表。
- [x] RAG Chat 拒答/低置信度自动沉淀及 PII 脱敏。
- [x] 缺口管理、用户未解决反馈、关联文档和回归 API。
- [x] 入库一致后自动执行 Retrieval + RAG 定向回归并写入 Eval 表。
- [x] `knowledge_gap_eval.py` 与 26 条用例。
- [x] 全部 GAP-AC 验收通过：26/26，治理链路通过率 100%，P95=5348.5ms。

### 4. GitHub 交付

Spec：[github-delivery.md](./specs/github-delivery.md)

- [x] 交付物、快速演示和完成边界 Spec。
- [ ] 根 README 与 Mermaid 架构图。
- [ ] 环境检查、前后端启动和 smoke-test 脚本。
- [ ] 幂等演示数据及 docId 19 到 50 导入说明。
- [ ] 全仓库脱敏与构建检查。
- [ ] 全部 GH-AC 验收通过并推送 GitHub。

### 5. 企业级 RAG 入库切片与 Query Rewrite

Spec：[enterprise-rag-ingestion-query-rewrite.md](./specs/enterprise-rag-ingestion-query-rewrite.md)

- [x] Markdown/PDF/DOC/DOCX 统一上传、MIME 校验、解析超时、页数/正文长度限制和 MinIO 原文件存储。
- [x] 结构化章节拆分、LLM 语义边界、超长拆分、短片段合并和 CL100K 真实 Token 计数。
- [x] 切片质量门禁：空片段、超限、原文覆盖、异常重复、标题/页码/source metadata 指标。
- [x] 文档版本键纳入 fileHash、parserVersion、chunkStrategyVersion 和 embeddingModel。
- [x] STAGING 三存储旁路写入、chunkId 校验、activeIndexVersion 原子切换和失败前旧版本保留。
- [x] 知识检索专用主改写、子查询、同义改写、实体/否定保护和加权 RRF。
- [x] 默认 Shadow Mode、超时回退 Original Query、候选预算和 Micrometer 指标。
- [x] `ingestion_chunk_eval.py`、`query_rewrite_eval.py` 及固定数据集。
- [x] Query Rewrite 在线 A/B 主流程验收：12/12，Recall@5 83.33% -> 100%，实体/否定保留率 100%。
- [ ] Query Rewrite 模型故障降级与非知识工具参数隔离验收。
- [ ] 在线运行两套评测并达到全部 CHUNK-AC / REWRITE-AC。

### 6. 前端产品叙事收口

Spec：[frontend-product-narrative-consolidation.md](./specs/frontend-product-narrative-consolidation.md)

- [x] USER 商城导航与 INTERNAL/ADMIN 四项运营后台导航分离。
- [x] 发布助手并入商品发布页，支持字段级采纳与 assistId 自动透传。
- [x] 交易问答隐藏 TopK、Rewrite 等技术参数，并移除 Raw Payload。
- [x] 知识库、知识缺口、检索验证和存储一致性合并为“知识治理”。
- [x] ReAct/DAG 与 AIOps 受控操作合并为“智能排查”。
- [x] Runtime 收口为“运行记录”，技术 Debug 页面受环境变量与 ADMIN 双重控制。
- [x] 新增按业务域拆分的 TypeScript API；前端生产构建和后端测试通过。
- [ ] 使用真实 USER/INTERNAL/ADMIN 账号完成五分钟演示验收。

### 7. Token 治理 MVP

Spec：[token-governance-mvp.md](./specs/token-governance-mvp.md)

- [x] RAG Chat、Agent Final、Agent Planner 场景预算。
- [x] RAG 问题/证据保护，历史会话、L2/L3/Profile 按优先级裁剪。
- [x] 模型输出 Token 上限与真实 Usage/本地估算双路采集。
- [x] `ai_token_usage`、Micrometer 指标及 INTERNAL 查询接口。
- [x] `token_governance_eval.py`、8 条固定用例和单元测试。
- [x] 8 条在线预览评测全部通过：预算遵守率 100%，P95=205.3ms。
- [ ] 使用可用模型复核真实 Usage 落库、RAG DONE Token 摘要和 Prometheus `ai_token_*` 指标。

## 完成定义

项目最终完成必须同时满足：

- [ ] 商品发布形成“建议 -> 采纳 -> 发布 -> 风险结果 -> 指标”闭环。
- [ ] AIOps 形成“发现 -> 审批 -> 修复 -> 验证 -> 解决/转人工 -> MTTR”闭环。
- [ ] RAG 形成“拒答 -> 缺口 -> 补知识 -> 入库 -> 自动回归”闭环。
- [ ] 全新 clone 可按 README 完成构建、启动和 10 分钟演示。
- [ ] 所有指标可追溯至固定数据集和评测报告。
