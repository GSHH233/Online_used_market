# RAG + Knowledge Agent 真实开发记录

更新时间：2026-10-13（补充交易并发、上传/监控/WS 边界与发布反馈解耦记录；历史评测日期保留）
记录目的：沉淀真实开发过程、当前状态、验收依据、历史问题和剩余工作。本文不是展示稿，也不是 README。

## 0. 记录口径

状态说明：

- `[x]` 对应代码或资产已存在；不等于当前版本已完整验收。测试结论必须另外注明报告与范围。
- `[~]` 已实现主体逻辑，但存在已知边界或待验证项。
- `[ ]` 尚未完成或仅有规划。
- `[!]` 历史上出现过问题，需要注意复盘或防止回归。

本文按“真实开发记录”整理，重点记录：

1. 为什么开发这个功能。
2. 实际做了什么。
3. 关联接口、表、脚本或核心类。
4. 用什么方式验收。
5. 中途出现过什么问题，最后怎么处理。
6. 当前还有什么没收口。

参考来源：

- `docs/DEVELOPMENT_CHECKLIST.md`
- `docs/specs/*.md`
- `docs/PROJECT_CORE_CODE_WALKTHROUGH.md`
- 个人链路记忆卡等学习材料已单独归档，不属于工程运行依赖。
- `eval/datasets/README.md`
- 当前代码中的 Controller、SQL、tools 目录。

冲突处理口径：当前行为以源码为准，测试结果以具体报告为准，Spec 表示要求而非实现证明。下文“历史通过”仅对当时的数据集、配置和实现有效；本次只核对文件，不能据此宣称当前工作区全量通过。旧阶段的故障和修复过程保留，过时的待办及调用方式更新，不删除失败证据。

## 1. 当前总状态

截至 2026-09-18，源码与仓库内报告反映的主要能力如下：

- [x] 文档入库、切片、Embedding、DB/ES/Milvus 三存储写入。
- [x] Hybrid + RRF 检索，支持 `vector-only / keyword-only / hybrid`。
- [x] RAG Chat，支持证据引用、拒答、价格忠实度约束、WebSocket 流式输出。
- [x] ReAct Agent 与 Graph/DAG Agent，支持工具调用、权限隔离、Trace。
- [x] Tool Execution Harness，支持超时、重试、Redis 快照恢复、fallback。
- [x] L1/L2/L3 记忆、Profile、Session Summary、Memory Graph。
- [x] DB/ES/Milvus 一致性检查、Repair API、Job 状态表、RabbitMQ 异步入库。
- [x] Agent Trace、Eval Dashboard、AIOps Event、白名单 Runbook 基础能力。
- [x] Token 治理 MVP：场景预算、优先级裁剪、输出限制、Usage 审计与指标。
- [x] AIOps 修复闭环、发布助手反馈闭环、知识缺口治理闭环已有实现及历史通过报告；范围见 1.2。
- [x] 统一智能排查入口、受控操作审批、Durable Runtime 与前端运行详情已存在。
- [x] Alertmanager 告警接入、去重聚合、中心化 Supervisor 多专家取证、受控 Docker Runbook 已存在。
- [x] 知识版本退役、保留期及受控回滚已实现，不能与直接覆盖旧索引混为一谈。
- [x] README、启动脚本及五分钟演示清单已存在，不再列为“尚未编写”。
- [~] 记忆身份隔离、摘要恢复、迁移和偏好生命周期已完成；检索候选范围与上下文组装仍需专项治理，见第 16 节。
- [~] 新环境复现、当前版本回归及指标口径统一仍需验收；不能把历史报告合并成“生产级全通过”。

### 1.1 Token 治理 MVP

开发原因：

现有切片阶段有真实 Token 计数，但在线 RAG/Agent 主要依赖字符数、topK 和 maxSteps，无法回答“一次调用用了多少 Token、超长上下文裁了什么、预算是否稳定”。

已完成：

- [x] 新增 `TokenScene` 和分场景输入/输出预算。
- [x] RAG Prompt 拆成回答约束、本轮问题、证据、L3、Profile、L2 和最近会话 section。
- [x] 超预算时优先裁剪低优先级记忆，本轮问题和证据最后保留。
- [x] 统一模型出口设置 `maxTokens`，优先读取 Spring AI `Usage`，缺失时以 CL100K 估算并标记。
- [x] 新增 `ai_token_usage`，按 `traceId + scene` 保存 Token、裁剪量、模型、重试和耗时。
- [x] 新增 Usage 明细、聚合指标和不调用模型的预算预览接口。
- [x] 新增 3 条单元测试、8 条固定评测数据和 `token_governance_eval.py`。

当前状态：

- [x] 历史记录中 Maven 编译及 Token/Planner 定向单元测试通过，本次未重跑。
- [x] `eval/reports/token_governance_eval_report.md` 已记录 8/8 通过。
- [~] 该报告验证预算预览等固定断言，不代表真实模型总成本已下降；节省比例仍需同模型、同输入的对照实验。

### 1.2 已有验收证据与限制

下表报告均位于 `eval/reports/`，是本地保留的历史证据；可能被 Git 忽略，公开交付时需另行归档脱敏副本。它们不是本次重新运行的结果。

| 能力 | 报告文件 | 已记录结果 | 不能外推的结论 |
| --- | --- | --- | --- |
| Token 治理 | `token_governance_eval_report.md` | 8/8 | 不代表真实成本节省率 |
| 混合 Planner | 历史 `hybrid_planner_eval_report.md`；当前 `hybrid_planner_eval_live_20260921-final-candidate-scoped.json` | 历史 8/8 全部规则路径；当前 live 13/13，4 条 `LLM_STRUCTURED`、2 条有限 Replan | 已证明受约束 LLM 规划与 React/Graph 有限补查；不外推为无限自治规划 |
| 发布反馈 | `publish_feedback_eval_report.md` | 20/20 | 混合接口用例耗时不是完整发布链路 P95 |
| 知识缺口 | `knowledge_gap_eval_report.md` | 26/26；采集、去重、治理断言 | 不等于未知问题回答全部正确 |
| 受控操作 | `controlled_operation_eval_report.md` | 12/12 | 不等于任意生产写操作安全 |
| AIOps 修复闭环 | `aiops_remediation_loop_eval_report.md` | 16/16 | 不代表跨所有故障类型自愈 |
| Durable Runtime | `durable_runtime_eval_report.md` | 15/15 | 固定恢复场景通过不等于端到端 exactly-once |
| 持续告警与协作 | `aiops_continuous_detection_eval_report.md` | 27/27 | 协作流程断言不能替代根因正确率评测 |
| 隔离 Docker Runbook | `docker_runbook_eval_report.md`、`docker_runbook_rollback_live_report.md` | 各 7/7，live=true | 仅对应测试目标，不授权操作现有业务容器 |
| 知识版本生命周期 | `knowledge_version_lifecycle_eval_report.md` | 4/4 | 不能仅凭此证明跨满 7 天的定时清理已验收 |
| DeepEval Agent | `deepeval_agent_eval_report.md` | 8/8；LLM judge=false | 工具/权限通过不等于任务完成质量已获 Judge 认可 |
| RAGAS Development v5 | `ragas_rag_development_v5_freeze_report.md` | 32 条，PASS；ID Recall=1.0000、Precision=0.6599；Judge=false | 开发集通过不是盲测通过 |
| RAGAS Holdout v4 | `ragas_rag_holdout_v4_blind_report.md` | 30 条，RISK；ID Recall=0.8722；Judge=true | 该盲测早于 Development v5 冻结，不能当作 v5 新盲测 |

时延必须区分脚本端到端、后端 total、retrieval、generation 与单个用例。例如 Development v5 同一报告的脚本 P95=2139ms、后端 total P95=1834ms，二者并不矛盾，不应混用。性能数据不能脱离并发、数据集和模型配置。

## 2. 主要开发阶段记录

### 2.1 发布助手 Pipeline

开发目标：

把原来的商品发布辅助逻辑拆成可维护的多阶段流水线，避免一个方法里混合描述优化、风险判断、定价建议和最终决策。

已完成：

- [x] 定义 `PublishInputContext`。
- [x] 定义 `PublishAssistResult`。
- [x] 发布助手拆分为 `optimize -> risk -> pricing -> decision`。
- [x] `PublishAssistPipelineRunner` 负责步骤编排和状态流转。
- [x] 风险、价格、描述优化步骤接入 RAG sources。

后续扩展：

- [x] 新增建议快照、采纳反馈、风险结果回流。
- [x] publish-assist 返回 `assistId / traceId`。
- [x] 商品创建时可关联发布建议。
- [x] 支持字段级采纳反馈和聚合指标 API。
- [x] `tools/publish_feedback_eval.py` 已有 20/20 历史报告；改动相关链路后需重新回归。

相关接口：

- `POST /api/ai/products/optimize`
- `POST /api/ai/products/publish-assist`
- `POST /api/ai/products/publish-assist/{assistId}/feedback`
- `GET /api/ai/products/publish-assist/{assistId}`
- `POST /api/ai/products/{productId}/risk-outcomes`
- `GET /api/ai/products/publish-assist/metrics`

相关表：

- `ai_publish_assist_record`
- `ai_publish_assist_feedback`
- `ai_product_risk_outcome`

历史问题：

- [!] 发布助手业务规则不能完全交给 LLM。风险判断必须前置，禁售/高风险类规则要有确定性 guardrail。
- [!] 价格建议曾出现证据外比例、折扣和价格区间，需要 prompt + 后处理双重限制。

## 3. RAG 知识库链路

### 3.1 Knowledge Index

开发目标：

把知识文档变成可召回、可引用、可修复的 chunk，并保证 DB、ES、Milvus 三边一致。

已完成：

- [x] `KnowledgeDoc / KnowledgeChunk / KnowledgeIndexTask` 已落地。
- [x] 文档创建接口：`POST /api/ai/knowledge/docs`。
- [x] 重建索引能力已实现；旧 `POST /api/ai/knowledge/reindex/{docId}` 已收口，修复/重建操作走受控计划。普通文档创建和首次入库仍有独立接口，不能混为一谈。
- [x] Markdown/PDF/DOC/DOCX 统一解析与结构优先切片已落地。
- [x] 支持 LLM 语义边界、超长拆分、短片段合并、overlap 和真实 Token 计数。
- [x] Embedding 写入向量存储流程已落地。
- [x] `VectorStore` 抽象已落地，支持 local / Milvus 切换。
- [x] `MilvusVectorStoreImpl` 支持 collection 初始化、upsert、search、deleteByDocId、deleteByChunkIds。
- [x] reindex 使用 STAGING 旁路写入 DB/ES/Milvus，校验 chunkId 后原子切换 `activeIndexVersion`。
- [x] 新版本失败时清理 STAGING 数据并保留旧 ACTIVE 版本，避免重建期间知识库不可用。
- [x] 知识检索支持主改写、子查询、同义改写、实体/否定保护和加权 RRF。

关键设计：

- DB 是 chunk 身份事实源。
- DB 先写入，获得 `chunkId`。
- ES 和 Milvus 使用同一个 `chunkId`。
- 这样才能做去重、引用定位、一致性检查和 Repair。

相关接口：

- `POST /api/ai/knowledge/docs`
- `POST /api/ai/operations/plans`（重建/修复计划，审批后执行）
- `GET /api/ai/knowledge/debug/milvus/by-doc/{docId}`

相关脚本：

- `src/main/resources/sql/rag_ingestion_job_mysql_migration.sql`

历史问题：

- [!] 早期 docId 只到 18，评测数据太少，容易过拟合当前知识库。
- 处理：补充 `retrieval_eval_seed_docs.json`，后续 docId 扩展到 19-50 一批演示数据。

### 3.2 Hybrid + RRF 检索

开发目标：

解决规则问法多样、同义表达、纯关键词漏召、纯向量忽略强关键词的问题。

已完成：

- [x] `RetrievalMode`：`vector-only / keyword-only / hybrid`。
- [x] 统一检索入口：`POST /api/ai/knowledge/search`。
- [x] 向量检索接入 Milvus。
- [x] 关键词检索优先走 ES BM25。
- [x] ES 不可用时 fallback 到 MySQL MVP 词面匹配。
- [x] Hybrid 使用 RRF 融合两路结果。
- [x] 检索结果按 `docId + chunkId` 去重。
- [x] metadata filter 和低置信度判定已接入。
- [x] RRF 后新增确定性二阶段 Rerank，融合原始排名、标题/正文覆盖和来源可信度。
- [x] 新增 MMR 去冗余和 Dynamic TopK；响应保留 `retrievalScore`、`rerankScore` 与评分原因。
- [x] 文档治理状态从 DRAFT 经验证进入 ACTIVE；索引代次使用 STAGING 旁路构建。同一逻辑知识新 revision 发布后旧版转 RETIRED；两套状态含义不同，见 3.3。
- [~] 存量文档按 legacy 模式兼容，需受控 reindex 后关闭兼容开关。

相关类：

- `KnowledgeSearchServiceImpl`
- `KeywordSearchServiceImpl`
- `RetrievalMode`
- `VectorStore`
- `MilvusVectorStoreImpl`

评测记录：

- [x] `tools/retrieval_eval.py`
- [x] `eval/datasets/retrieval_eval_dataset.json`
- [x] `eval/datasets/retrieval_eval_hard_dataset.json`
- 历史 hard case：61 条。
- 历史结果：hybrid Recall@5 = 100.00%，MRR = 0.930。
- 2026-08-08：接入 RAGAS 多相关 Gold；初始 ID Context Recall = 100%，Precision = 30.83%，据此启动 Rerank 与生命周期治理。
- 新验收目标：ID Context Recall >= 90%，ID Context Precision >= 55%。
- 2026-08-11：冻结 Holdout v2 后，仅按聚合失败类型构造 30 条 Development v3；加入交易安全语义归一化和显式 Evidence Plan。Development v3 从 Precision 47.00% / Recall 72.22% 提升至 71.22% / 97.22%，Retrieval P95 从 5.92s 降至 599ms。
- 2026-08-11：50 条 Regression 复验通过，Precision 59.43%、Recall 97.00%、Retrieval P95 274ms；修复“流量卡不实名”被误判为账号所有权的规则泛化问题。Holdout v2 未重跑。
- 框架决策：当前 Boot 3.5.6 + Spring AI 1.0.3 保持不动；Spring AI 2.0 仅在独立迁移分支评估，不在检索算法实验中同时换框架，也暂不混用 LangChain4j。
- 2026-08-12：Development v5 冻结报告 PASS，但未开启 LLM Judge；此前 Holdout v4 的 RISK 结论保留。后续只从聚合失败类型构造开发集，冻结后使用未见过的新 Holdout，不以修改原盲测题目来取得通过。

历史问题与修复：

- [!] `retrieval_eval_dataset.json` 曾出现 JSON/编码问题。
  - 处理：修复为合法 UTF-8 JSON。
  - 经验：评测集本身也是工程资产，必须保证字段稳定和可解析。

- [!] hybrid 检索曾出现 `vectorHits=0, keywordHits=0, total=0`。
  - 原因：`riskLevel` filter 与 chunk metadata 中的 `unknown` 不一致，过滤条件误杀。
  - 处理：对齐 metadata 写入、过滤值和查询条件。
  - 经验：metadata filter 必须在 debugInfo 中输出。

- [!] ES index 不存在时 keyword 分支长期 fallback 到 MySQL。
  - 现象：`Index ai_knowledge_chunk not found`。
  - 处理：新增 ES index 初始化、ApplicationReadyEvent 二次初始化、历史 chunk 回填、reindex 前 ensure index。
  - 验证目标：`debugInfo` 中应看到 `esUsed=true,fallback=false`。

### 3.3 知识版本发布与回滚

- [x] 新索引代次 STAGING -> 三存储一致性验证 -> 切换 `activeIndexVersion`，失败保留原可用版本。
- [x] 新文档 revision 激活后，旧 revision 转 RETIRED 并排除检索；按保留期安排物理清理，默认 7 天。
- [x] 回滚选择保留期内旧 revision，经受控审批重新构建索引、验证并切回 ACTIVE，原新版转 RETIRED；保留操作、Trace 与审计信息。
- [~] 发布/回滚/保留期内不可检索已有历史报告；过期清理已有 `KnowledgeRetiredVersionCleanupServiceImplTest`，本次未重跑。跨保留期在线调度、清理与回滚竞争、并发发布仍应逐项核对专项验收，不自动标记完成。

## 4. RAG Chat 生成链路

开发目标：

在检索证据基础上生成用户可读回答，并控制幻觉、拒答、关键字段遗漏和 LLM 不稳定。

已完成：

- [x] `RagChatService.chat(...)`。
- [x] HTTP 评测入口：`POST /api/ai/rag-chat/eval`。
- [x] WebSocket 入口：`/ws/ai/rag-chat`。
- [x] WebSocket 消息协议：`CHAT_START / CHAT_ACK / CHAT_DELTA / CHAT_SOURCE / CHAT_DONE / CHAT_ERROR / CHAT_CANCEL / CHAT_PING / CHAT_PONG`。
- [x] cancel 中断链路。
- [x] 默认使用 hybrid 检索。
- [x] lowConfidence 从简单阈值升级为融合分、词面覆盖、来源多样性、keyword signal、margin 联合判定。
- [x] LLM 调用加入重试。
- [x] LLM 失败时基于 sources 做降级回答。
- [x] 证据不足时拒答并要求补充必要字段。
- [x] 价格类回答增加证据忠实度约束。
- [x] 用户输入关键字段补漏后处理。
- [x] RAG Chat outcome 事件接入知识缺口治理。

相关类：

- `RagChatServiceImpl`
- `ChatGenerationServiceImpl`
- `RagChatWebSocketHandler`
- `RagChatEvalController`
- `KnowledgeGapServiceImpl`

评测记录：

- [x] `tools/rag_chat_eval.py`
- [x] 数据集复用 `eval/datasets/retrieval_eval_hard_dataset.json`
- 历史结果：Source Gold Hit 100.00%，Reject Accuracy 100.00%，Errors 0。

历史问题与修复：

- [!] 模型曾编造 `0.8-0.85倍`、`5%-10%`、`15-25%议价空间`。
  - 处理：修改 prompt 和后处理规则，禁止无证据金额、比例、折扣、价格区间。
  - 评测：加入 forbidden keywords 检查。

- [!] LLM 请求超时或生成失败导致评测失败。
  - 处理：增加 LLM retry；失败时基于 sources 生成 fallback answer。
  - 经验：RAG 可用性不能完全押在 LLM 单次成功上。

- [!] 回答偶发遗漏用户输入关键字段。
  - 处理：prompt 要求覆盖关键字段，后处理补漏。
  - 覆盖字段：无原盒、仅主机、遥控器、桨叶、缺电池、电池健康、快门次数等。

- [!] 模糊问题容易被强答。
  - 处理：加入拒答和澄清规则，缺商品名称、品类、凭证时要求用户补充。

## 5. 记忆系统

开发目标：

让 RAG Chat 和 Agent 能利用上下文与长期偏好，但不能让历史记忆覆盖当前事实和 RAG 证据。

已完成：

- [x] L1 短期记忆：`ConversationMemoryService`，基于 session window。
- [x] L2 持久记忆：`L2MemoryService`、`ai_l2_memory_record`。
- [x] L2 语义召回：保存 `embeddingJson`，查询时 query embedding + cosine similarity。
- [x] L2 默认 importance = `0.3`。
- [x] L3 KV Session Summary。
- [x] L3 同步写入 L2：`memoryType=SESSION_SUMMARY`，importance = `0.8`。
- [x] Profile 结构化偏好注入。
- [x] Memory Graph：节点、边、图扩展评测。
- [x] Agent Runtime Context 汇总 L1/L2/L3/Profile/Graph。

优先级规则：

```text
当前用户输入 > 工具 Observation/RAG 证据 > L3 Session Summary > L2/Profile/Graph > L1
```

这是应用层上下文取舍和任务字段冲突规则，不是安全指令层级：系统约束、服务端权限校验始终有效；用户陈述不能覆盖可信工具返回的客观事实。先判断相关性、时效、归属与类型，再排序和裁剪，并非默认全量注入。实际存储与已知风险见第 19、16 节。

相关接口：

- `POST /api/ai/l2-memory/debug/write`
- `GET /api/ai/l2-memory/debug/query`
- `GET /api/ai/l2-memory/debug/profile`
- `POST /api/ai/memory-graph/debug/extract`
- `GET /api/ai/memory-graph/debug/nodes`
- `GET /api/ai/memory-graph/debug/edges`

相关表：

- `ai_l2_memory_record`
- `ai_memory_node`
- `ai_memory_edge`

评测记录：

- [x] `tools/memory_priority_eval.py`
- [x] `eval/datasets/memory_priority_eval_dataset.json`
- 评测层次：store、recall、consolidation、context assembly、graph expansion。

历史问题与修复：

- [!] L2 debug 查询时 GET + body 参数导致查不到。
  - 处理：统一使用 Query Params。

- [!] Profile 接口初期查不到结构化偏好。
  - 处理：明确 L2 原始记忆和 Profile 结构化视图的职责边界。

- [!] AIOps eval 第 6/12 轮延迟过高。
  - 原因：L3 Session Summary 每 3 轮同步触发 LLM refresh。
  - 处理：AIOps 快速链路跳过同步 L3 refresh，避免污染 P95。

当前边界：

- [~] L2 语义召回当前主要在 Java 内对候选计算相似度，不是 pgvector 级别检索。
- [~] Runtime Context 有长度裁剪和结构化组装，但不是完整语义 compaction。

## 6. Knowledge Agent / ReAct / Graph Agent

开发目标：

让系统从固定 RAG 问答扩展为可按问题调用工具的 Agent 链路。

已完成：

- [x] ReAct Agent：Hybrid Planner -> 工具调用 -> Observation -> 有限 Replan -> final answer。
- [x] Graph/DAG Agent：工具节点并行执行，支持拓扑调度。
- [x] Hybrid Planner：明确意图走规则快路径，INTERNAL 复杂问题可选结构化 LLM 规划，失败回退规则计划。
- [x] 服务端计划校验：工具白名单、依赖校验、敏感参数重写、工具去重和总步数限制。
- [x] Observation 驱动的有限 Replan：最多 2 轮、每轮最多补 1 个未调用工具，不允许权限扩张。
- [x] Tool Registry 统一注册工具。
- [x] Agent Runtime Context 注入记忆和权限上下文。
- [x] Trace 持久化记录 plan、tool call、observation、final answer 和异常。
- [x] USER/INTERNAL/ADMIN 权限分层。

相关接口：

- `POST /api/ai/agent/investigate`（统一智能排查入口）
- `POST /api/ai/agent/react-chat`
- `POST /api/ai/agent/graph-chat`
- `GET /api/ai/agent/traces`
- `GET /api/ai/agent/traces/{traceId}`
- `GET /api/ai/agent/eval-dashboard`

相关工具：

- `knowledge_search`
- `mysql_read`
- `log_query`
- `vector_debug`
- `prometheus_query`
- `profile`
- `harness_fault`

评测记录：

- [x] `tools/agent_eval.py`
- [x] `tools/react_agent_eval.py`
- [x] `eval/datasets/agent_eval_dataset.json`
- [x] `eval/datasets/react_agent_eval_dataset.json`
- [x] `tools/hybrid_planner_eval.py`
- [x] `eval/datasets/hybrid_planner_eval_dataset.json`
- [x] 历史在线报告 8/8 全部走规则快路径且 Replan 为 0，保留为优化前基线。
- [x] 当前 live 13/13 通过：4 条走 `LLM_STRUCTURED`，React/Graph 各 1 条触发 `replanRounds=1`；模型只看到并只能采用规则候选工具，未出现额外工具扩张。报告：`eval/reports/hybrid_planner_eval_live_20260921-final-candidate-scoped.json`。
- [x] Planner 定向单测 9/9、完整 Java 回归 147 条中 146 条通过、1 条跳过。

历史问题与修复：

- [!] 初版 Knowledge Agent 出现工具存在但端到端链路没有跑通。
  - 处理：增强 `agent_eval.py`，断言 trace、source、工具、回答关键词、forbidden keywords。

- [!] AIOps 复合问题显式要求查知识库，但 planner 没有调用 `knowledge_search`。
  - 原因：只识别风险、价格、发布类业务词，没有识别“知识库/规则/证据/RAG/source”。
  - 处理：新增 `isKnowledgeSearchIntent`。

- [!] 普通 USER 可能间接触发内部工具。
  - 处理：内部工具在 USER 模式返回 DENIED observation。

当前边界：

- [~] 当前使用 Spring AI 完成受约束的结构化模型规划，不是完全交给模型自治的原生 Function Calling Loop。
- [~] LLM Planner 默认关闭，便于无 Token 的本地调试；开启后只处理 INTERNAL 复杂多工具问题。
- [~] Replan 是服务端受限状态机，不支持任意新工具、任意写操作或无限循环。

### 6.1 Durable Runtime 与协作职责

- [x] `AgentRuntimeServiceImpl` 已实现运行/节点/checkpoint 持久化及暂停、恢复、取消、重放控制；入口为 `/api/ai/agent/runs`。
- [x] 前端已有 `AgentRunListView.vue`、`AgentRunDetailView.vue`；内部排查入口为 `AgentInvestigationView.vue`。
- ReAct/Graph 是执行策略，不是让普通用户理解和选择的产品菜单。统一入口路由不能据此推断所有 AIOps 协作都通过 ReAct/Graph。
- AIOps 多专家由 `BuiltinSupervisorCollaborationEngine` 组织，属于中心化 Supervisor 协作。专家直接采集各自证据，再统一诊断，不是 P2P，也不能宣称每个专家都有独立长期记忆或自治工具循环。
- Runtime checkpoint 保存整次运行进度；Harness snapshot 保存工具结果。两者职责不同，不能用工具快照代替任务恢复和写操作幂等。

## 7. Tool Execution Harness

开发目标：

为 Agent 工具调用提供统一可靠性保护，避免单个工具超时或失败拖垮整条 Agent 链路。

已完成：

- [x] 工具级 timeout。
- [x] 失败重试和 backoff。
- [x] Redis 快照保存与恢复。
- [x] fallback observation。
- [x] Micrometer / Prometheus 指标。
- [x] 故障注入工具 `harness_fault`。
- [x] Harness debug 接口。

相关接口：

- `POST /api/ai/harness/debug/execute`

相关脚本：

- `tools/harness_eval.py`
- `eval/datasets/harness_eval_dataset.json`

历史评测覆盖：

1. 正常成功。
2. 首次失败后重试成功。
3. 连续异常后 fallback。
4. 连续超时后 timeout fallback。
5. 成功调用写 snapshot。
6. 后续失败从 snapshot restore。
7. USER 模式拒绝内部故障注入工具。

历史问题与修复：

- [!] `ToolExecutionSnapshotStore` Bean 缺失导致启动失败。
  - 处理：补齐 Redis snapshot store Bean 或配置条件。

- [!] Harness eval 里出现 `tool execution failed, tool=harness_fault` 日志。
  - 结论：这是故障注入用例的预期日志，不代表业务失败。

当前边界：

- [~] Java `future.cancel(true)` 不能替代底层 HTTP/SDK timeout，底层客户端仍需要配置连接和读取超时。
- 工具重试耗尽后先尝试可用快照，再返回降级 Observation；不是先随意生成降级答案再恢复快照。是否换工具、是否 Replan 由上层规划与预算决定，Harness 不保证工具一定成功。

## 8. AIOps 辅助排查与修复闭环

开发目标：

让内部用户可以通过 Agent 编排日志、指标、只读 SQL、知识库和 Runbook，形成排查与受控修复闭环。

已完成基础能力：

- [x] Prometheus query 工具。
- [x] 日志查询工具。
- [x] MySQL 只读查询工具。
- [x] 向量/召回调试工具。
- [x] Graph Agent 编排式排查。
- [x] AIOps Event 持久化。
- [x] 白名单 Runbook。
- [x] 审批、拒绝、执行详情、MTTR API。
- [x] 修复后统一验证。
- [x] 验证失败转人工。
- [x] Alertmanager Webhook、fingerprint 去重聚合、异步协作与持续发现评测已接入。
- [x] Supervisor 汇总指标、日志、最近变更等专家证据，生成受约束诊断和白名单修复建议。
- [x] 隔离 Docker restart、健康检查、失败回滚已有 live 报告；仍受目标白名单、审批与审计约束。

相关接口：

- `POST /api/ai/tools/prometheus/query`
- `POST /api/ai/tools/mysql/read`
- `POST /api/ai/tools/mysql/milvus/read`
- `POST /api/ai/aiops/events`
- `GET /api/ai/aiops/events`
- `GET /api/ai/aiops/events/{eventId}`
- `PATCH /api/ai/aiops/events/{eventId}`
- `POST /api/ai/aiops/events/{eventId}/approve`
- `POST /api/ai/aiops/events/{eventId}/reject`
- `GET /api/ai/aiops/events/{eventId}/remediation`
- `GET /api/ai/aiops/events/metrics/mttr`
- `GET /api/ai/aiops/runbooks`
- `POST /api/ai/aiops/runbooks/execute`（保留 dry-run；真实写执行已封闭，不能绕过受控计划）
- `POST /api/ai/aiops/alerts/alertmanager`（机器告警入口，使用专门的接入校验）
- `POST /api/ai/operations/plans`
- `GET /api/ai/operations/{operationId}`
- `POST /api/ai/operations/{operationId}/approve`
- `POST /api/ai/operations/{operationId}/reject`
- `POST /api/ai/operations/{operationId}/execute`

相关表：

- `aiops_event`
- `aiops_action`

评测记录：

- [x] `tools/aiops_eval.py`
- [x] `tools/aiops_runbook_eval.py`
- [x] `tools/aiops_remediation_loop_eval.py`
- [x] `eval/datasets/aiops_eval_dataset.json`
- [x] `eval/datasets/aiops_runbook_eval_dataset.json`
- [x] `eval/datasets/aiops_remediation_loop_eval_dataset.json`

历史问题与修复：

- [!] Prometheus 查询表达式含空格时报 `Invalid character ' ' for QUERY_PARAM`。
  - 处理：查询参数需要 URL encode，后端/脚本统一处理。

- [!] Prometheus target 只抓到自身，抓不到 Spring Boot。
  - 原因：Prometheus 跑在 VMware Docker 中，不能用 Docker Desktop 的 `host.docker.internal`。
  - 处理：target 改为 Windows 宿主机 VMnet8 地址。

- [!] AIOps eval 出现 latencyExceeded。
  - 原因 1：final answer 阶段 LLM 长尾。
  - 处理 1：AIOps 快速链路用确定性 `buildFastOperationalAnswer`。
  - 原因 2：L3 同步刷新拖慢。
  - 处理 2：AIOps 快速链路跳过 L3 refresh。

当前边界：

- [~] 当前自动修复是受控白名单 Runbook，不是任意生产故障自愈。
- [x] Alertmanager 接入已有实现和 27/27 持续发现历史报告，不再列为未开发。
- [x] 修复闭环已有 16/16 历史报告；仍须区分事件审批、操作计划审批与具体执行记录，不以事件已审批绕过计划校验。
- [~] 多专家当前主要是确定性取证与汇总，诊断质量仍需带根因 Gold 的专项评测；流程通过不等于根因全对。

## 9. 三存储一致性、Repair 与 RabbitMQ 入库

开发目标：

让 RAG 知识库从“能检索”升级为“能发现索引不一致、能修复、能追踪任务状态”。

已完成：

- [x] 结构化一致性服务：`KnowledgeStorageConsistencyService`。
- [x] `GET /api/ai/storage/debug/consistency`。
- [x] `tools/storage_consistency_eval.py`。
- [x] `tools/storage_repair.py`。
- [x] RAG Ingestion Job。
- [x] Repair API。
- [x] Job 状态表扩展。
- [x] 批量 Repair。
- [x] RabbitMQ 异步入库。
- [x] Recovery Scheduler 处理 stale queued/running task。

相关接口：

- `GET /api/ai/storage/debug/consistency`
- `POST /api/ai/knowledge/jobs/ingest/{docId}`
- `POST /api/ai/knowledge/repair/{docId}`（旧入口，返回受控操作迁移提示）
- `POST /api/ai/knowledge/repair/batch`（旧入口，不再直接批量执行写操作）
- `GET /api/ai/knowledge/jobs/{taskId}`
- `GET /api/ai/knowledge/jobs`

相关表：

- `ai_knowledge_index_task`

相关脚本：

- `tools/storage_consistency_eval.py`
- `tools/storage_repair.py`
- `tools/rabbit_ingestion_eval.py`
- `eval/datasets/rabbit_ingestion_eval_dataset.json`

历史问题与修复：

- [!] Repair 返回 `status=FAILED` 但 message 写“任务已完成”。
  - 处理：根据任务最终状态生成 message，失败时明确返回失败原因。

- [!] Repair 写入后立即校验，ES/Milvus 短暂不可见导致误判失败。
  - 处理：ES 写入后 refresh；VERIFY 阶段加入短重试，记录 `verifyAttempts`。

- [!] Batch Repair 返回 `submittedTasks=0` 被误解为没查到。
  - 原因：默认只修不一致 doc，抽查 doc 全部一致时不会提交任务。
  - 处理：响应增加 `checkedDocsDetail` 和 `skippedDocs`。

- [!] RabbitMQ eval 出现 401。
  - 原因：Spring Security 后脚本缺少 token。
  - 处理：评测脚本支持 bearer token。

当前边界：

- [~] 三存储采用最终一致性和 Repair，不是跨 DB/ES/Milvus 分布式事务。
- Repair/reindex 的能力保留，但执行入口已经收口为 `operations/plans -> ADMIN 审批 -> 执行 -> 验证`。RabbitMQ 负责异步调度，不负责绕过审批，也不自动保证跨存储原子性。

## 10. Agent Trace 与 Eval Dashboard

开发目标：

让 Agent 运行过程和评测结果可追踪、可回放、可在前端 Dashboard 展示。

已完成：

- [x] Agent Trace PG 化。
- [x] Trace Run / Step 持久化。
- [x] Eval Run / Metric / Case Result 持久化。
- [x] Trace 异常也落库。
- [x] Eval Dashboard 后端接口。

相关接口：

- `GET /api/ai/agent/traces`
- `GET /api/ai/agent/traces/{traceId}`
- `GET /api/ai/agent/eval-dashboard`

相关表：

- `ai_agent_trace_run`
- `ai_agent_trace_step`
- `ai_eval_run`
- `ai_eval_metric`
- `ai_eval_case_result`

历史问题：

- [!] 原先 Agent Trace 放 MySQL 与 Memory Graph / Eval PG 化方向不一致。
  - 处理：Trace / Eval / AIOps Event 逐步 PG 化。

- [!] `agentTraceRunMapper` 曾因 Mapper/Bean 定义冲突启动失败。
  - 处理：检查 Mapper 扫描和数据源配置。

## 11. 知识缺口治理闭环

开发目标：

把 RAG 的低置信度、拒答、用户未解决反馈沉淀为知识缺口，并在补充文档后自动定向回归。

已完成：

- [x] Spec：`docs/specs/knowledge-gap-governance.md`。
- [x] RAG Turn Audit。
- [x] 知识缺口表。
- [x] 回归记录表。
- [x] RAG Chat outcome 监听。
- [x] PII 脱敏。
- [x] query fingerprint 去重。
- [x] 用户未解决反馈接口。
- [x] 缺口列表、详情、更新、关联文档、回归 API。
- [x] 入库一致后执行 Retrieval + RAG 定向回归并写入 Eval 表。
- [x] `knowledge_gap_eval.py` 与 26 条用例。

相关接口：

- `POST /api/ai/knowledge/gaps/feedback/unresolved`
- `GET /api/ai/knowledge/gaps`
- `GET /api/ai/knowledge/gaps/{gapId}`
- `PATCH /api/ai/knowledge/gaps/{gapId}`
- `POST /api/ai/knowledge/gaps/{gapId}/link-doc`
- `POST /api/ai/knowledge/gaps/{gapId}/regress`
- `GET /api/ai/knowledge/gaps/{gapId}/regressions`
- `GET /api/ai/knowledge/gaps/metrics`

当前状态：

- [x] 主体逻辑和 `knowledge_gap_eval_report.md` 26/26 历史报告已存在，不再标记为尚未执行。
- [~] 该报告包含治理门禁断言；“正确拒绝关闭缺口”也可能是通过用例，不能解释为所有缺口均已被知识更新解决。

设计边界：

- `MISSING_FIELDS` 不应直接补知识库，因为问题是用户输入缺字段，不是知识缺口。
- 低置信度问题需要去重、聚合和人工 triage，不能全部无脑补文档。

## 12. 安全与权限

开发目标：

防止普通用户通过 Agent 间接访问内部日志、数据库、索引、监控或修复能力。

已完成：

- [x] Spring Security 接入。
- [x] JWT。
- [x] MySQL 用户角色字段。
- [x] USER / INTERNAL / ADMIN 权限分层。
- [x] 内部工具 `internalOnly`。
- [x] Controller 层使用 `ToolAccessGuard` 做内部/管理员校验。
- [x] 评测脚本支持 token。

历史问题：

- [!] 加入 Spring Security 后，旧评测脚本出现 401。
  - 处理：脚本统一增加 bearer token 参数。

- [!] 普通用户如果能触发 MySQL/ES/Milvus/日志查询，会泄露内部信息。
  - 处理：USER 模式下内部工具返回 DENIED observation。

当前边界：

- [~] 仍需检查前端页面是否根据 role 隐藏内部入口。
- [~] GitHub 公开前必须确认无 API Key、数据库密码和真实业务数据。

## 13. 配置、部署与基础设施记录

已使用中间件：

- MySQL
- PostgreSQL
- Redis
- RabbitMQ
- Elasticsearch
- Milvus
- Prometheus
- MinIO

已完成：

- [x] Docker Compose 管理大部分中间件。
- [x] Windows/IDEA 本地 profile：`application-idea.yml`。
- [x] `.env.example`。
- [x] Prometheus scrape Spring Boot。
- [x] 容器重启脚本曾编写用于 VMware 环境。

2026-06-29 启动修复记录：

- [x] 修复多 DataSource 同时 `@Primary` 导致的注入冲突。
  - 原因：`memoryGraphDataSource / mysqlDataSource / dataSource` 多个候选。
  - 处理：保留 `mysqlDataSource` 作为主数据源，相关类显式 `@Qualifier`。

- [x] 修复 `FileController -> FileServiceImpl` 依赖链启动失败。
  - 原因推断：`application-idea.yml` 中 MinIO access-key / secret-key 为空。
  - 处理：当时补齐本地开发凭据；该临时配置不是生产凭据管理方案。

- [x] 修复 IDEA profile 中 RabbitMQ password 默认空和缩进问题。
  - 处理：补齐本地开发默认值及缩进；部署环境通过环境变量提供独立凭据。

当前启动注意：

- `OPENAI_API_KEY` 必须通过环境变量注入。
- `MYSQL_PASSWORD` 要与本机 MySQL 一致。
- `REDIS_PASSWORD` 与实际 Redis 实例一致，通过 IDEA 环境变量显式设置，不在开发记录中保留密码。
- ES 如果未开启安全认证，`ELASTICSEARCH_USERNAME/PASSWORD` 可为空。

## 14. 自动化评测资产

当前 eval 数据集目录：

- `eval/datasets/retrieval_eval_dataset.json`
- `eval/datasets/retrieval_eval_hard_dataset.json`
- `eval/datasets/retrieval_eval_seed_docs.json`
- `eval/datasets/agent_eval_dataset.json`
- `eval/datasets/react_agent_eval_dataset.json`
- `eval/datasets/memory_priority_eval_dataset.json`
- `eval/datasets/harness_eval_dataset.json`
- `eval/datasets/aiops_eval_dataset.json`
- `eval/datasets/aiops_runbook_eval_dataset.json`
- `eval/datasets/aiops_remediation_loop_eval_dataset.json`
- `eval/datasets/publish_feedback_eval_dataset.json`
- `eval/datasets/knowledge_gap_eval_dataset.json`
- `eval/datasets/rabbit_ingestion_eval_dataset.json`

执行说明集中在 [评测脚本指令](docs/EVAL_SCRIPT_COMMANDS.md)，参数以当前脚本 `--help` 为准，避免在多份文档维护相互冲突的命令。PowerShell 不使用 CMD 的 `^` 续行，也不要直接粘贴 `<JWT>` 占位符。

已补充的评测入口包括 `controlled_operation_eval.py`、`durable_runtime_eval.py`、`aiops_continuous_detection_eval.py`、`docker_runbook_eval.py`、`knowledge_version_lifecycle_eval.py`、`token_governance_eval.py`、`hybrid_planner_eval.py`、`deepeval_agent_eval.py` 和 `ragas_rag_eval.py`，均位于 `tools/`。

安全的参数检查示例（不执行远程业务）：

```powershell
python tools/controlled_operation_eval.py --help
python tools/durable_runtime_eval.py --help
python tools/memory_priority_eval.py --help
python tools/storage_repair.py --help
```

执行 Repair/Runbook 测试前确认脚本使用当前受控计划接口及 ADMIN 审批，不再沿用“INTERNAL token 直接 force-reindex”的旧指令。在线测试使用隔离文档、账号及明确允许的测试容器；不默认重启、删除已有业务容器，不把真实 Token 写入本文件。

评测规则：

- 测试失败时优先判断是实现问题、数据问题、环境问题还是测试断言问题。
- 不允许为了通过测试只改问题问法来规避失败。
- 如果测试集本身不合理，可以修改，但必须记录原因。
- 每条重要评测应能关联 `caseId / requirementId / traceId / taskId / gapId / eventId`。

## 15. Spec 范式与追踪表记录方式

项目收尾采用 Spec-driven Development：

```text
Spec -> 数据迁移 -> API/Service -> 自动化评测 -> README/演示 -> 完成标记
```

追踪表不应该只写在一个地方，应该分层记录：

### 15.1 Spec 中记录“验收追踪矩阵”

Spec 应记录：

- requirementId
- 验收标准
- 对应接口
- 对应表
- 对应 eval dataset
- 对应 eval script
- 必须追踪的字段

示例：

| requirementId | 验收点 | 脚本 | 追踪字段 |
| --- | --- | --- | --- |
| RAG-AC-001 | hybrid Recall@5 达标 | `retrieval_eval.py` | caseId、docId、chunkId |
| AGENT-AC-001 | 工具选择正确 | `react_agent_eval.py` | traceId、tool、observation |
| AIOPS-AC-001 | 修复失败转人工 | `aiops_remediation_loop_eval.py` | eventId、actionId、verificationStatus |
| GAP-AC-001 | 补文档后回归关闭 | `knowledge_gap_eval.py` | gapId、docId、evalRunId |

### 15.2 数据库中记录“运行时事实”

运行时追踪表记录真实发生了什么：

- `ai_agent_trace_run`
- `ai_agent_trace_step`
- `ai_eval_run`
- `ai_eval_metric`
- `ai_eval_case_result`
- `ai_knowledge_index_task`
- `aiops_event`
- `aiops_action`
- `ai_knowledge_gap`
- `ai_knowledge_gap_regression`
- `ai_publish_assist_record`
- `ai_publish_assist_feedback`
- `ai_product_risk_outcome`

### 15.3 测试报告中记录“实际执行结果”

测试报告记录：

- 总通过率。
- P50/P95。
- 失败 case。
- 失败断言。
- 关联 traceId/taskId/gapId/eventId。
- 修复建议。

结论：

> Spec 里写“应该怎么测”，追踪表里写“实际发生了什么”，测试报告里写“这次跑出来怎么样”。

## 16. 下一阶段计划（2026-09-18）

目标：先修正确性和评测缺口，再做交付回归。继续复用现有 Spring AI、受控操作、Runtime 和评测资产，不同时引入新框架或迁移全部存储。本节的目标表与实际实施记录分开阅读。

对应 Spec：[记忆正确性、质量评测与发布收口](docs/specs/memory-quality-and-release-hardening.md)。状态为 `IN_PROGRESS`；v1.1 已完成规格复核，包含身份/接口/迁移契约、验收门槛、14 项 MEM/EVAL/REL 追踪与 19 组代表性操作步骤。实施与验收以该 Spec 的追踪矩阵为准。

### 16.1 阶段一：记忆身份与恢复正确性（P0）

实施前源码发现的风险：`InMemorySessionSummaryServiceImpl` 创建摘要时把 `sessionId` 写入 `userId`；恢复时用语义检索 Top1 后才过滤 `SESSION_SUMMARY`。`L2MemoryServiceImpl.retrieve` 在 userId 为空时没有用户过滤，sessionId 参数未用于筛选。下述单测覆盖了部分修复，但尚无在线越权或真实数据验证。

| 编号 | 待开发内容 | 验收标准 |
| --- | --- | --- |
| MEM-01 | 真实认证 userId 贯穿 L1/L3/Runtime；会话缓存采用用户+会话复合键；缺身份时拒绝读取私有记忆 | 两用户使用相同 sessionId 仍互不读取；空身份不退化为全库查询；客户端伪造 userId 无效 |
| MEM-02 | 区分跨会话长期偏好与当前任务摘要；摘要按 userId+sessionId+类型精确读取最新有效版本，不走语义 Top1 | 两会话互不覆盖；其他记忆高分不能挤掉摘要；进程重启后恢复正确最新摘要；切换商品不带入旧任务字段 |
| MEM-03 | 核定 L2 继续使用 MySQL；补齐/核对 MySQL 初始化、迁移与启动检查，标注旧 PG SQL 的非默认用途 | 空库和已有库均能初始化；重复执行不破坏记录；文档、Mapper 和实际连接一致 |

先补复现失败的服务层单测，再修改实现，最后用隔离账号做 HTTP 回归；不直接删除历史记忆。旧错误归属数据需先备份、可预览迁移，无法可靠确认归属的记录不得自动挂到其他用户。

#### 2026-09-18 实施记录（阶段一进行中）

- MEM-01：L1/L3 改用真实 userId + sessionId 复合键；RAG WebSocket 的 CHAT_START 校验 JWT 并取签名 userId；浏览器会话内的 conversationId 在 WebSocket 重连后保持不变，仍绑定签名 userId。Runtime 在入口持久化 owner/role，后台线程不再依赖 HTTP ThreadLocal；INTERNAL 只能查看本人 Run，ADMIN 可查看全部。拒绝访问写入 PG 审计表；异步执行、恢复和重放前从 MySQL 重检账号状态与当前角色，创建时 role 仅作为审计快照。
- MEM-02：L3 用 userId + sessionId + SESSION_SUMMARY 精确恢复，拒绝错 owner/错 session；L2 语义候选查询排除其他会话摘要。新增 MySQL `ai_memory_slot`，以 expectedRevision CAS 更新 currentRecordId；旧提取结果不能覆盖新摘要，竞争失败后重读最新版本。商品显式切换会清除旧任务字段。
- 离线验证基线：2026-09-18 时记忆和 Agent 定向 26 条通过，完整 Java 测试 128 条中 127 通过、1 跳过，前端构建通过。后续验收以下方带时间和报告的新记录为准。
- 在线验收（2026-09-18）：本机依赖端口及 `/actuator/health` 正常；无 token 和伪造 token 的 WebSocket CHAT_START 均返回 401。两个新建 `mem_eval_*` USER 使用同一 conversationId，A 首轮、B 首轮、A 重连三次聊天均收到 CHAT_DONE；MySQL 中 A/B 摘要分别为 2/1 条，对方测试标记写入数均为 0。报告：`eval/reports/memory_scope_live_eval_report_20260918-235731.json`。这证明连接重建后的作用域隔离，不等于应用进程重启后的恢复验收。
- 重启验收（2026-09-19）：8080 进程在上述在线测试后重启（新进程启动于 00:17:45）；同一个合成 USER 用原 conversationId 再次登录、继续 WebSocket 会话并收到 CHAT_DONE。新摘要 ID 1228 大于重启前 ID 1226，结构化机型字段保持一致；报告：`eval/reports/memory_restart_resume_eval_20260919-002224.json`。这是单实例进程重启冒烟，不替代跨实例 CAS、不同商品切换及更强的精确恢复对照测试。
- 双会话验收（2026-09-19）：同一合成 USER 的旧会话摘要保持不变，新会话独立保存 MacBook 型号且未带入旧机型，报告：`eval/reports/memory_two_session_eval_20260919-004253.json`。商品切换红灯基线：旧 8080 进程的 `eval/reports/memory_product_switch_eval_20260919-004655.json` 仍保留旧型号/事实且缺少新品牌。代码随后在明确切换时重置商品字段，单测覆盖普通追问、持久化元数据回读与第三轮 LLM 旧字段干扰；该阶段尚未加载新代码。`mvnw.cmd -o test`：131 条，130 通过、1 跳过。阶段证据索引：`eval/reports/memory_stage1_acceptance_20260919.md`。
- 商品切换在线复测（2026-09-19）：8080 新进程 01:03:03 启动后，沿用同一个测试脚本和合成账号，`eval/reports/memory_product_switch_eval_20260919-010427.json` 为 PASS：先记录 iPhone 13/无发票，再明确切换为华为手机，新摘要设置 Huawei，清除旧型号与旧事实。上条保留旧进程失败基线；不能据此推断任意新商品类别或跨实例版本竞争已通过。
- 新进程双会话回归（2026-09-19）：`eval/reports/memory_two_session_eval_20260919-011029.json` 为 PASS，旧会话摘要未变化，新会话型号独立；与商品切换复测共同验证了本轮修改未破坏基础会话隔离。
- 高分 FACT 干扰在线验收（2026-09-19）：`eval/reports/memory_fact_interference_eval_20260919-011457.json` 为 PASS。合成会话同时写入精确 iPhone 摘要和 importance=1.0、与查询匹配的 MacBook FACT；CHAT_DONE 的组装信息仍将 iPhone 放在 L3 摘要，MacBook 仅进入独立 L2 区域，证明语义/重要度不能替代摘要精确读取。
- 异步身份与 CAS 验收（2026-09-20）：`eval/reports/runtime_identity_eval_20260920-042629.json` 为 PASS，Runtime 的 owner/role、异步摘要归属、INTERNAL 跨 owner 拒绝和 ADMIN 查询均符合预期；`eval/reports/memory_summary_cas_eval_20260920-043207.json` 使用两个独立 MySQL 连接同时更新同一 revision，仅一个写入生效且无孤儿摘要。完整 Java 回归 137 条，136 通过、1 跳过。
- Runtime 审计与撤权验收（2026-09-20）：`eval/reports/runtime_identity_eval_20260920-044231.json` 验证跨 owner DENY 已持久化；`eval/reports/runtime_role_revocation_eval_20260920-044342.json` 验证合成 INTERNAL 降权后旧 Token 启动、ADMIN 重放均被拒绝且可审计，合成用户清理后残留数为 0。MEM-01 标记 VERIFIED。
- MEM-03 迁移验收（2026-09-20）：新增 MySQL 标准空库 schema、旧库幂等升级初始化器、生命周期字段/索引和 ACTIVE 召回门禁；旧 PostgreSQL L2 脚本标记为非默认历史用途。隔离库验证空库反复初始化、旧库升级、第二次迁移无变更、幂等唯一约束均通过；项目库先备份后分类，1250 条前后数量一致，35 条可证 owner 转 ACTIVE，1215 条不明 owner 保持 QUARANTINED，未删除内容。在线 HTTP 测试证明 ACTIVE 可见、QUARANTINED 即使完全命中也不可见，测试记录已清理。报告：`eval/reports/memory_mysql_migration_eval_20260920-184435.json`、`memory_mysql_migration_eval_20260920-184457.json`、`memory_migration_live_eval_20260920-185618.json`。MEM-03 标记 VERIFIED。
- MEM-04 生命周期验收（2026-09-21）：L2 新增稳定 `sourceEventId`、payload hash、用户级偏好槽位、纠错 supersede 与删除墓碑；Profile 改为槽位当前 ACTIVE 记录优先，并兼容尚未槽位化的历史偏好。真实 HTTP 20 并发同事件得到 1 次 CREATED、19 次 REPLAYED，同事件不同 payload 返回 409；纠错后 revision=2 且只展示新值，删除后 revision=3、旧事件重放不复活，Memory Graph 无孤儿节点/边，测试数据全部清理。首轮并发暴露的 MySQL 死锁和 RR 旧快照问题已通过槽位 upsert 串行化与锁定读修复；幂等键按 owner、sourceEventId、类型、范围和 slotKey 派生，同一源事件可安全写多个槽位。报告：`eval/reports/memory_lifecycle_eval_20260921-021954.json`。MEM-04 标记 VERIFIED。
- MEM-05 有效召回验收（2026-09-21）：L2 候选改为 MySQL 侧 owner/type/status/scope 过滤和可配置的 1000 条有界候选；relevance 独立决定是否进入候选，importance 仅参与相关候选排序。Runtime 审计记录检索模式、fallback 原因、候选截断和采用/排除 ID，不记录记忆正文。隔离 HTTP 验证第 201/1000 条 Gold 均召回，无关 importance=0.8 不能越过门槛；真实 embedding 已写入并走 `EMBEDDING` 模式，测试数据清零。报告：`eval/reports/memory_recall_eval_20260921-031816.json`。MEM-05 标记 VERIFIED。
- MEM-06 五层质量验收（2026-09-21）：使用 48 条 Development 与 24 条独立 Holdout 覆盖身份隔离、更新、否定、20 次重放、恢复和长历史。评测调用真实 `AgentRuntimeContextBuilder`，Store/Recall/Consolidation/Context/Graph 指标均为 1.0，冲突、越权和执行错误均为 0；完整 Java 回归 144 条中 143 条通过、1 条跳过。报告：`eval/reports/memory_quality_development_report_20260921-111736.json`、`memory_quality_holdout_report_20260921-111934.json`。MEM-06 标记 VERIFIED。
- EVAL-01 Planner/Replan 验收（2026-09-21）：live 13/13，4 条真实 `LLM_STRUCTURED`，React/Graph 各 1 条有限 Replan；候选工具约束、权限和预算门禁通过。报告：`eval/reports/hybrid_planner_eval_live_20260921-final-candidate-scoped.json`。
- EVAL-02 Supervisor 验收（2026-09-21）：24 条 Development、12 条独立 Holdout 覆盖证据一致、冲突、缺失、超时；根因、证据支持、引用存在与保留判断均为 1.0。三轮交错对照中并行 P95 为 31.9/37.5ms，串行为 77.6/87.0ms。当前为结构化确定性 Supervisor，实际模型 Token=0，不外推为 LLM 多 Agent 推理收益。报告：`eval/reports/aiops_diagnosis_quality_development_report.json`、`aiops_diagnosis_quality_holdout_report.json`。
- EVAL-03 新盲测（2026-09-21）：在首次运行前冻结 30 条 Holdout v6，五类各 6 条且与 v4/v5 无重复问题。首次 deterministic RAGAS 结果为 RISK：ID Recall=0.7556、Precision=0.5439、case recall pass=0.6333，错误 0；多跳证据和拒答证据召回是主要失败组。当前评测进程没有 Judge 密钥，LLM Judge 尚未运行，不能签字。报告：`eval/reports/ragas_rag_holdout_v6_blind_deterministic_report.md`。

### 16.2 阶段二：记忆更新与有效召回（P1，依赖阶段一）

偏好写入已从直接追加收敛为幂等槽位生命周期，Profile 只读取当前 ACTIVE 槽位。通用 L2 召回已改为 owner/type/status/scope 前置过滤和最多 1000 条有界候选，相关性先过门槛，importance 仅用于已相关候选排序；Runtime 记录采用/排除 ID、截断状态、检索模式和降级原因，不记录敏感记忆正文。

| 编号 | 待开发内容 | 验收标准 |
| --- | --- | --- |
| MEM-04 | 以来源事件/轮次幂等写入；区分用户陈述、工具证据、模型推断；增加偏好覆盖、失效与删除语义 | 同一事件重放 20 次不产生 20 份逻辑重复记忆；明确纠正后只有新值有效；助手猜测不升级为已确认事实 |
| MEM-05 | 类型过滤前移到候选选择；Profile 用有效时间/版本解决冲突；相关性门槛与 importance 分离 | VERIFIED；201/1000 条 Gold、无关高 importance、模型不可用 fallback、真实 embedding 写入与正常语义召回均通过 |
| MEM-06 | `VERIFIED`：48 条 Development、24 条独立 Holdout、可复现生成器和五层评测器 | 真实 Runtime Context 组装口径下 Development 48/48、Holdout 24/24；五层指标均为 1.0，冲突/越权/错误均为 0 |

先测 200/1000 条候选的质量和 P95 基线，再决定是否需要 pgvector/Milvus。不能把“迁移 PG”当成修复身份、更新和语义检索问题的替代方案。检索质量阈值在运行前写入专项 Spec，不边看结果边降标准。

### 16.3 阶段三：补齐 Agent 与 RAG 的质量证据（P1）

| 编号 | 待验收内容 | 验收标准 |
| --- | --- | --- |
| EVAL-01 | `VERIFIED`：混合 Planner 真实 LLM 规划及有限 Replan | live 13/13；4 条 `LLM_STRUCTURED`、2 条有限 Replan；规则路由、非法计划、空结果补查、预算耗尽、权限拒绝及候选工具约束均有证据 |
| EVAL-02 | `VERIFIED`：Supervisor 多专家诊断质量与价值 | Development 24/24、Holdout 12/12；四类证据场景、串并行三轮对照、根因/引用/保留判断门禁通过；确定性模式 Token=0，边界已记录 |
| EVAL-03 | DeepEval/RAGAS Judge 与独立盲测 | 标记 Judge 开关/模型/参考答案/人工校准；工具权限断言与回答质量分开；实现冻结后再使用全新 Holdout，保留失败报告 |
| EVAL-04 | Token 成本治理真实效果 | 同一模型、输入和并发对照预算前后 usage、截断量、重试、fallback、质量；估算与供应商返回值分开，不凭预览耗时推导节省百分比 |

### 16.4 阶段四：发布与演示收口（P2，功能冻结后）

- [ ] 核查版本清理与回滚竞争、Runtime 中断恢复、写操作幂等和失败转人工是否有与当前实现匹配的测试；缺项先补，不因旧报告全绿而跳过。
- [ ] 定向单元测试 -> 后端构建 -> 前端构建 -> 隔离 API 回归 -> USER/INTERNAL/ADMIN 浏览器验收；`-DskipTests package` 只证明构建，不能代替测试。
- [ ] 为本次验收记录 commit/工作区快照、模型、数据集版本、配置摘要、Judge、并发、fallback、原始报告路径；统一 README、Spec 追踪表和简历的指标口径。
- [ ] 从新 clone 按 README、`scripts/start-backend.ps1`、`scripts/start-frontend.ps1` 复现；配置先备份，不覆盖现有本地凭据，不把秘密提交 Git。
- [ ] 复用 `docs/FIVE_MINUTE_DEMO_CHECKLIST.md`，展示发布建议及反馈、RAG 缺口治理、内部诊断及审批验证；不新增演示中心，不强制制作图片视频。

### 16.5 暂不纳入本轮

P2P 多 Agent、全量框架迁移、通用语义 compaction、图像理解、任意生产故障自愈、L2 无基准直接迁移向量库均不是本轮完成条件。已有 Alertmanager、有限 Replan、Durable Runtime 不再重复列为“待开发”。MEM-01~06、EVAL-01/02 已验收，下一步按顺序补齐 EVAL-03/04 的 RAG/Judge 与成本质量证据。

## 17. 面试时可以诚实说明的边界

- 当前是混合 Planner：规则快路径 + 可选结构化 LLM 规划，不是完全自治的原生 Function Calling Loop。
- 当前 Graph/DAG 支持规则或结构化计划生成、拓扑并行和有限 Replan；写操作仍必须进入受控审批链路。
- AIOps 自动修复是白名单 Runbook，不是任意生产故障自愈。
- RAG confidence 是经验公式，不是概率校准模型。
- 业务规则断言与 RAGAS/DeepEval 质量评测并存。每份报告分别说明是否开启 LLM Judge；历史 DeepEval 8/8 没有开启 Judge，不能替代任务质量和人工评估。
- 三存储治理是最终一致性 + Repair，不是分布式事务。
- Runtime Context 有长度裁剪，但还不是完整上下文压缩系统。

## 18. 最终完成定义

项目真正收口需要同时满足：

- [x] 商品发布“建议 -> 采纳 -> 发布 -> 风险结果 -> 指标”已有实现与历史反馈评测；最终版本重跑见 16.4。
- [x] AIOps“发现 -> 诊断 -> 计划审批 -> 修复 -> 验证 -> 解决/转人工 -> 指标”已有实现与分段历史报告；不代表当前环境全部重新验收。
- [x] RAG“拒答 -> 缺口 -> 补知识 -> 入库 -> 自动回归”已有实现与治理测试；具体缺口是否解决仍受质量门禁约束。
- [ ] 第 16 节身份、摘要恢复及偏好更新风险完成定向修复与回归。
- [ ] 新 clone 可以按 README 完成后端启动、前端启动和基础演示。
- [ ] 所有核心指标可以追溯到固定数据集、Trace 表和评测报告。

## 19. 2026-08-27 源码核对：记忆系统真实实现

本节保留 2026-08-27 的源码核对记录，并于 2026-09-18 复核修正存储与恢复边界；未检查部署数据库中的真实数据。

### 19.1 记忆层次不要混成“三层长期记忆”

当前输入不是一层长期记忆。实现包含 L1、L2、L3 和 Profile 这些不同职责，另有 Memory Graph，由 Runtime Context 统一组装：

1. **当前输入**：本轮问题，只在本次请求中使用，不落为会话历史本身。
2. **L1 短期会话记忆**：`InMemoryConversationMemoryServiceImpl` 按认证 userId + sessionId 复合键保存内存中的问答窗口；配置默认保留 5 轮 Q/A（即最多 10 条字符串），空闲超过 30 分钟过期。RAG Chat 读取最近 10 条，Agent Runtime 读取最近 6 条，因此面试时不要笼统说“固定最近十次”。
3. **L2 长期语义记忆**：`L2MemoryServiceImpl` 持久化到 `ai_l2_memory_record`，记录 `FACT`、`PREFERENCE`、`SESSION_SUMMARY` 等类型、重要度、metadata 和 embedding JSON。偏好通过 sourceEventId 幂等、槽位 current record、supersede 与 tombstone 管理生命周期；通用查询先在 MySQL 按 owner、类型、状态和会话范围筛选，最多评估 1000 条候选，再以语义/词面相关性过门槛、importance 辅助排序并取 TopK。候选截断和 embedding 降级会显式审计；当前仍不是 pgvector/Milvus 级别的直接向量检索。
4. **L3 当前会话任务摘要**：`InMemorySessionSummaryServiceImpl` 按认证 userId + sessionId 保存结构化 KV 状态，包括当前目标、商品、品类、品牌、型号、已知事实、缺失字段、最近风险/价格/发布结论和下一步追问。规则抽取每轮执行，LLM 默认第 1 轮及每 3 轮补充抽取；摘要以 `SESSION_SUMMARY` 写回 L2，通过 MySQL slot revision/CAS 精确恢复并拒绝迟到结果覆盖。
5. **Profile 结构化画像**：`UserProfileServiceImpl` 不是与 L2 完全并列的另一套原始存储，而是从 L2 当前有效偏好槽位聚合出的品牌、品类、价格区间、成色、交易偏好、风险容忍度和文案风格。它只用于默认参数、排序和表达风格，不能覆盖本轮事实、RAG 证据和风险规则。

此外，项目还有独立的 **Memory Graph**：节点和边持久化在 PostgreSQL 的 `ai_memory_node/ai_memory_edge`，用于技能、兴趣、事实、风险和实体关系；它不是最近对话窗口，也不是 Profile 的同义词。

### 19.2 一次请求如何组装记忆

`AgentRuntimeContextBuilder` 会按 session/user 读取 L3、Profile、L1、L2 和 Graph，生成统一 Runtime Context，并设置优先级：

```text
当前用户输入 > 工具 Observation / RAG 证据 > L3 任务摘要 > L2 / Profile / Graph > L1 最近对话
```

上述优先级适用边界同第 5 节，并不允许用户覆盖系统安全约束。Runtime Context 自身会做长度和单行裁剪（默认上限约 3200 字符），生成出口另有 Token 预算治理，两者不是同一个预算单位。`RagChatServiceImpl` 在启用持久化且非 eval 的正常链路中，回答完成后追加 L1、刷新 L3、写入 L2 FACT；偏好另行抽取为 PREFERENCE。切换商品时旧 L3 专有字段的清理与真实重启恢复仍待在线验收。

### 19.3 面试时应说的“已实现”与“边界”

推荐表述：

> 我的 Agent 记忆分成当前输入、L1 短期会话、L2 长期语义记忆和 L3 当前任务摘要，Profile 是从 L2 偏好记忆聚合出的结构化视图，Memory Graph 则保存实体和关系。L1 用 session window 控制上下文长度；L2 持久化事实和偏好，并结合 embedding 与词面分数按需召回；L3 保存当前商品、任务目标、缺失字段和最近结论。请求到来时由 Runtime Context 统一组装，当前输入和 RAG/工具证据优先级最高，历史记忆只能补全，不能覆盖当前事实。

不要说成以下未经源码支持的内容：

- L1 已经使用 Redis 或数据库做分布式持久化；当前实现是 JVM 内存窗口。
- L2 已经直接使用 Milvus/pgvector 做记忆检索；当前实现主要是 Java 对最多 200 条候选计算相似度。
- L3 与 Profile 都是同一种“长期记忆”；L3 是会话任务状态，Profile 是偏好视图。
- 系统会把所有历史对话都塞进 Prompt；实际有候选上限与优先级裁剪，但 Profile 当前是混合召回后才过滤类型，尚不能宣称所有类型均已检索前隔离。

### 19.4 数据源核验备注

- `config/MySqlMapperScanConfig` 把 L2 Mapper 包显式绑定到 `mysqlSqlSessionTemplate`；当前代码的 L2 数据源方向是 MySQL，不是“已迁移 PG”。
- 仓库中的 `sql/l2_memory_pg16_init.sql` 使用 PostgreSQL（`BIGSERIAL`、`JSONB`、`plpgsql`）语法；当前源码未发现对应的自动初始化器引用。这是历史 SQL 与默认 Mapper 路径不一致，后续需核对 MySQL 初始化及已有部署数据，不能直接把该 PG SQL 当成 L2 当前启动脚本。
- Memory Graph 明确使用独立的 `memoryGraphJdbcTemplate` 和 PostgreSQL 数据源；不要把 Memory Graph 的 PG 配置直接推断成 L2 也一定走 PG。

## 20. 2026-09-18 文档清理记录

- 更新停留在 7 月的头部状态；补入持续告警、中心化协作、Durable Runtime 和知识版本回滚。
- 将 Token、Planner、发布反馈、知识缺口等“尚未在线测试”改为已有历史证据及真实覆盖限制；保留 Holdout RISK，不合并为全量通过。
- 清理旧 Repair/Runbook 直接写入和 INTERNAL 强制重建指令，明确受控计划审批执行路径。
- 明确 MySQL L2 与 PG Memory Graph、工具快照与运行 checkpoint、规则协作与自治 Agent 的边界；补充记忆源码风险，不虚构已修复。
- 保留历史故障记录，将尚未完成、可选扩展和发布前回归分开；删除文档内无必要的开发密码明文，未修改实际配置。
- 本次仅整理 `agent.md`，未执行模型调用、数据库迁移、容器操作或服务端回归；后续完成标记必须有新的证据。

## 21. 2026-10-13 架构修复清单复核与实施

依据当前工作区复核外部修复方案，逐项处理记录见 `docs/specs/architecture-hardening-review-and-repair.md`。附件中的旧结论没有直接作为当前源码事实。

- [x] 自定义 MySQL SessionFactory 接入 MyBatis-Plus 分页拦截器与 YAML/GlobalConfig；分页上限 100；商品/订单复用分页 total，避免重复 count；SQL 日志默认 NoLoggingImpl。
- [x] 订单创建先 CAS 抢商品再插订单与快照；MySQL 事务失败回滚；pay/deliver/finish/cancel 使用原状态条件更新，集中合法流转；取消和完成只修改 LOCKED 商品。
- [x] 图片上传移除匿名放行；大小检查基于实际字节，MIME 必须匹配允许扩展名；MinIO bucket 只缓存成功初始化。
- [x] HTTP/WS 共享 Origin 配置，保留每次 CHAT_START JWT 和用户/会话隔离；可选握手 Authorization 身份一致性检查；每用户 Redis 原子 60 秒窗口限流，超限 429。
- [x] 监控接口不再匿名访问；单独抓取凭证仅允许 GET prometheus；管理端口可显式配置，默认保持原业务端口；ADMIN 审批/执行增加声明式权限并保留业务 Guard。
- [x] 商品模块通过本领域校验接口与不可变发布事件扩展 AI 能力；归属和采纳参数发布前验证；反馈在 AFTER_COMMIT 后通过独立 REQUIRES_NEW 事务关联，关联失败不回滚已提交商品。
- [x] L1 默认 Redis List 原子追加/裁剪/TTL；L3 复用已有 MySQL slot revision/CAS，不替换成无版本 Hash。
- 自动验证：完整 Maven 310 条，308 通过、2 跳过、0 失败；前端 vue-tsc/Vite 构建通过；最后补强的发布事务 8 条定向测试通过。原始测试证据在 `target/surefire-reports/`，日志在 `tmp/architecture-hardening-maven-test.log`。
- [~] 当前 8080 未运行；真实 MySQL 双买家并发、MinIO 上传、Redis TTL/进程恢复、Prometheus 抓取和浏览器联调待应用重启后执行，未冒充已通过。
- [~] 本地提交后事件仍可能在进程崩溃时丢失，监听也仍同步执行；outbox/持久重试需另立迁移和补偿设计。
- [ ] Flyway、根 Compose、RAG 类拆分、全量分包与目录迁移留作独立阶段。历史 PG L2 脚本不能加入默认 PG 初始化；本轮没有删除个人文档、测试资产或操作既有数据库/容器。
