# Online Used Marketplace 收尾开发清单

更新时间：2026-08-20
当前阶段：核心 AI 能力与单业务域 AIOps MVP 已完成应用边界验收；知识版本生命周期已补齐“暂存验证、7 天保留、受控回滚、延迟清理”。

## 已完成能力

- [x] Hybrid + RRF 检索，支持 vector-only、keyword-only、hybrid、metadata 过滤和低置信度判定。
- [x] 二阶段 Deterministic Rerank + MMR 去冗余 + Dynamic TopK，加入显式 Evidence Plan 和证据缺口优先选择；保留原始 RRF 分用于拒答置信度，生命周期候选批查与重排指标已接入。
- [x] 知识 DRAFT→索引验证→ACTIVE→RETIRED 生命周期：旧 revision 不参与检索、默认保留 7 天、定时清理 DB/ES/Milvus chunk，并支持 ADMIN 审批后的受控回滚。
- [x] 知识版本生命周期真实端到端验收：`eval/reports/knowledge_version_lifecycle_eval_report.md` 4/4 通过；验证发布、不可检索、回滚、Operation execution/trace/audit 回填和 DB/ES/Milvus 一致性。
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
- [x] AIOps 持续发现与多 Agent 协作：Alertmanager 验签接入、fingerprint 去重聚合、RabbitMQ 异步调查、三类专家并行取证、白名单计划、ADMIN 审批、独立验证和 MTTD/MTTR。

## 收尾阶段

### 1. AIOps 修复闭环

Spec：[aiops-remediation-loop.md](./specs/aiops-remediation-loop.md)

- [x] Spec、状态机、权限和指标公式。
- [x] PostgreSQL 迁移：审批、解决时间、转人工和 Action 幂等字段。
- [x] 审批/拒绝/处置详情/MTTR API。
- [x] Runbook 执行后统一验证及失败转人工。
- [x] `aiops_remediation_loop_eval.py` 与 16 条用例。
- [x] 全部 AIOPS-AC 验收通过。

持续发现 Spec：[aiops-continuous-detection-multi-agent.md](./specs/aiops-continuous-detection-multi-agent.md)

- [x] Prometheus 告警规则与 Alertmanager webhook 配置模板。
- [x] Webhook secret 校验、payload hash 幂等、fingerprint 去重和 groupKey 关联。
- [x] RabbitMQ 主队列、有限重试、DLQ、手动 ACK 和 delivery 状态追踪。
- [x] Metrics/Log/Change Specialist 并行取证、Supervisor 证据汇总及缺失源降级。
- [x] 确定性白名单修复计划、ADMIN 审批、修复后独立健康验证和 source event 回填。
- [x] UTC 时间归一化及 MTTD、automation repair、full MTTR 聚合。
- [x] `aiops_continuous_detection_eval.py` 27/27 通过，P95=518.1ms；全量 Maven 测试通过。
- [ ] 在目标部署环境加载 Prometheus rule 与 Alertmanager 配置，并完成真实告警风暴/断连压测。

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
- [x] RAGAS 多相关 Gold 标注、ID Context Precision/Recall 门槛与生命周期只读评测脚本。
- [x] 记录旧在线版本基线：ID Context Recall 95.00%、Precision 35.17%、P95 8.02s。
- [x] 第一轮 Rerank 在线诊断：Recall 100%、Precision 37.33%，确认排名先验过高和动态阈值过宽。
- [x] 第二轮 Rerank 调整：业务概念归一化、通用词过滤、权重及动态阈值收紧，新增互补证据保留测试。
- [ ] 存量 legacy DRAFT 文档完成受控 reindex，并关闭 `KNOWLEDGE_ALLOW_LEGACY_DRAFTS`。
- [x] 新 Rerank 版本 10 条在线 Smoke Gate：ID Context Precision 70.00%、Recall 100%、错误数 0。
- [x] RAGAS 扩充为 10 条 Smoke、50 条 Regression 和 15 条独立 Holdout，共 75 条；数据校验确认 Holdout 无问题文本泄漏。
- [x] 50 条 Regression 发布门禁通过：ID Context Precision 66.23%、Recall 100%、50/50 完整召回、错误数 0。
- [x] RAG 端到端 P95 降至 4s 内：Regression P95=3.60s，后端 Retrieval P95=310ms。
- [x] 独立 Holdout 已完成首次盲测并冻结结果：ID Context Precision 56.67%、Recall 73.33%、10/15 完整召回，结论为 RISK；禁止直接按 Holdout 题面调参，失败模式转入下一轮开发集。
- [x] 从 Holdout v1 聚合失败类型构造 24 条 Development v2，不复用原题文本；基线 Precision 41.04%、Recall 66.67%、14/24 完整召回。
- [x] 完成多意图 Facet Coverage、双路 ES 词面/概念候选、价格边界意图与确定性证据快速路径；Development v2 达到 Precision 59.24%、Recall 95.83%、22/24 完整召回，Retrieval P95=606ms。
- [x] 架构调整后 50 条 Regression 复验通过：Precision 66.67%、Recall 100%、Retrieval P95=327ms、E2E P95=3.59s。
- [x] 新建 20 条 Holdout v2 并完成数据泄漏校验与首次盲测；结果冻结为 RISK：Precision 48.08%、Recall 62.50%、11/20 完整召回，禁止针对逐题结果继续调参。
- [x] 从 Holdout v2 聚合类型构造 30 条来源独立的 Development v3，不复用原题；改造前 Precision 47.00%、Recall 72.22%、18/30 完整召回、Retrieval P95=5.92s。
- [x] 完成交易安全结算语义归一化、显式多跳 Evidence Plan、按证据需求扩展候选预算和证据缺口优先重排；Development v3 通过：Precision 71.22%、Recall 97.22%、28/30 完整召回、Retrieval P95=599ms。
- [x] 修复“实名”子串误触发账号所有权计划的回归；50 条 Regression 通过：Precision 59.43%、Recall 97.00%、Retrieval P95=274ms、E2E P95=3.66s，noisy 分组 Recall=100%。
- [x] 数据集校验覆盖 149 条，Holdout 泄漏对为 0；Holdout v2 结果继续冻结，未在本轮重跑。
- [x] 对 docId 19–68 的 DB、ES、Milvus 正文执行 Unicode 转义与汉字占比审计：未发现 `U+FFFD`，抽样汉字占比 86%–100%；此前乱码来自 PowerShell 终端显示，不执行错误的数据重建。
- [x] 新建并封存 25 条 Holdout v3，SHA-256=`760d339c...f7df8a4`；一次性 `--summary-only` 盲测完成且未输出逐题结果。
- [x] Holdout v3 首次结果冻结为 RISK：Precision 64.53%、Recall 84.00%、完整召回率 72%、Retrieval P95=539ms；聚合缺口为多跳证据召回、交易安全精度、noisy 与 hard query。
- [x] 仅按 Holdout v3 聚合缺口构造 30 条 Development v4，未查看、改写或重跑 Holdout v3 单题；数据集校验覆盖 204 条，泄漏检查通过。
- [x] Development v4 改造前基线为 RISK：Precision 64.39%、Recall 87.78%、完整召回通过率 80%、Retrieval P95=314ms。
- [x] 扩展交易安全、定价依据、来源引用、规则版本、配件和流量卡等 Evidence Plan；RRF 后按证据需求保留候选，并对强规则语义族做保守去重。
- [x] 修复 ES 启动回填遗漏 `indexVersion`/`logicalKnowledgeId` 导致正确关键词命中被生命周期治理过滤的问题。
- [x] Development v4 最终门禁通过：Precision 74.56%、Recall 100%、30/30 完整召回、Retrieval P95=359ms、E2E P95=3.89s、错误数 0。
- [x] 50 条 Regression 最终复验通过：Precision 66.67%、Recall 100%、50/50 完整召回、Retrieval P95=314ms、错误数 0；E2E P95=4.23s，尾延迟主要来自模型生成而非检索。
- [ ] 冻结当前实现后创建来源独立的 Holdout v4，以 `--summary-only` 只执行一次；不得继续使用 Holdout v3 调参。
- [ ] 独立分支评估 Spring AI 1.0.3 -> 2.0.x 迁移；当前 Boot 3.5/AI 1.0 版本不在本轮检索指标实验中直接升级。
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
- [x] AIOps 形成“发现 -> 审批 -> 修复 -> 验证 -> 解决/转人工 -> MTTR”闭环（应用边界 E2E 27/27；生产监控部署验收另列）。
- [ ] RAG 形成“拒答 -> 缺口 -> 补知识 -> 入库 -> 自动回归”闭环。
- [ ] 全新 clone 可按 README 完成构建、启动和 10 分钟演示。
- [ ] 所有指标可追溯至固定数据集和评测报告。
