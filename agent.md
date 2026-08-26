# RAG + Knowledge Agent 真实开发记录

更新时间：2026-07-30  
记录目的：沉淀真实开发过程、当前状态、验收依据、历史问题和剩余工作。本文不是展示稿，也不是 README。

## 0. 记录口径

状态说明：

- `[x]` 已实现并至少通过过一次接口/脚本验证。
- `[~]` 已实现主体逻辑，但仍需要最终回归或演示前确认。
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
- `docs/PROJECT_FIVE_CHAINS_MEMORY_CARD.md`
- `eval/datasets/README.md`
- 当前代码中的 Controller、SQL、tools 目录。

## 1. 当前总状态

截至 2026-07-30，项目核心能力已经从“RAG 问答 Demo”推进到以下工程形态：

- [x] 文档入库、切片、Embedding、DB/ES/Milvus 三存储写入。
- [x] Hybrid + RRF 检索，支持 `vector-only / keyword-only / hybrid`。
- [x] RAG Chat，支持证据引用、拒答、价格忠实度约束、WebSocket 流式输出。
- [x] ReAct Agent 与 Graph/DAG Agent，支持工具调用、权限隔离、Trace。
- [x] Tool Execution Harness，支持超时、重试、Redis 快照恢复、fallback。
- [x] L1/L2/L3 记忆、Profile、Session Summary、Memory Graph。
- [x] DB/ES/Milvus 一致性检查、Repair API、Job 状态表、RabbitMQ 异步入库。
- [x] Agent Trace、Eval Dashboard、AIOps Event、白名单 Runbook 基础能力。
- [x] Token 治理 MVP：场景预算、优先级裁剪、输出限制、Usage 审计与指标。
- [~] AIOps 修复闭环、发布助手反馈闭环、知识缺口治理闭环已经实现主体逻辑，但演示前仍建议跑最终回归。
- [~] GitHub 交付材料、README、启动脚本、演示数据仍属于收尾项。

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

- [x] Maven 编译及 Token/Planner 定向单元测试通过。
- [ ] 后端启动后运行 8 条在线预览评测并保存报告。

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
- [~] 需要最终跑 `tools/publish_feedback_eval.py` 确认全部 PUB-AC 验收。

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
- [x] 重建索引接口：`POST /api/ai/knowledge/reindex/{docId}`。
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
- `POST /api/ai/knowledge/reindex/{docId}`
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
- [x] 新文档执行 DRAFT→三存储验证→ACTIVE；同一逻辑知识新 revision 发布后自动退役旧版本。
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
- [~] Hybrid Planner 与 Graph Replan 共 7 条单元测试通过；在线 Planner 评测待执行。

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
- `POST /api/ai/aiops/runbooks/execute`

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
- [~] Alertmanager 真实告警接入可后置。
- [~] AIOps 修复闭环虽已实现主体逻辑，但演示前应跑最终 `aiops_remediation_loop_eval.py`。

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
- `POST /api/ai/knowledge/repair/{docId}`
- `POST /api/ai/knowledge/repair/batch`
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

- [~] 主体逻辑已完成。
- [~] 演示前应跑 `tools/knowledge_gap_eval.py` 确认全部 GAP-AC 通过。

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
  - 处理：本地默认改为 `minioadmin/minioadmin`。

- [x] 修复 IDEA profile 中 RabbitMQ password 默认空和缩进问题。
  - 处理：`RABBITMQ_PASSWORD` 默认 `guest`。

当前启动注意：

- `OPENAI_API_KEY` 必须通过环境变量注入。
- `MYSQL_PASSWORD` 要与本机 MySQL 一致。
- `REDIS_PASSWORD` 若本机是 `123456`，需要在 IDEA 环境变量中显式设置。
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

常用评测脚本：

```bash
python tools/retrieval_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/rag_chat_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/react_agent_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/agent_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/memory_priority_eval.py --base-url http://localhost:8080 --bearer-token <JWT>
python tools/harness_eval.py --base-url http://localhost:8080 --bearer-token <INTERNAL_JWT>
python tools/aiops_eval.py --base-url http://localhost:8080 --bearer-token <INTERNAL_JWT>
python tools/aiops_runbook_eval.py --base-url http://localhost:8080 --admin-bearer-token <ADMIN_JWT> --internal-bearer-token <INTERNAL_JWT> --user-bearer-token <USER_JWT> --doc-id 19
python tools/aiops_remediation_loop_eval.py --base-url http://localhost:8080 --admin-bearer-token <ADMIN_JWT> --internal-bearer-token <INTERNAL_JWT> --user-bearer-token <USER_JWT>
python tools/publish_feedback_eval.py --base-url http://localhost:8080 --admin-bearer-token <ADMIN_JWT> --internal-bearer-token <INTERNAL_JWT> --user-bearer-token <USER_JWT> --other-user-bearer-token <OTHER_USER_JWT>
python tools/knowledge_gap_eval.py --base-url http://localhost:8080 --admin-bearer-token <ADMIN_JWT> --internal-bearer-token <INTERNAL_JWT> --user-bearer-token <USER_JWT> --doc-id 19
python tools/storage_consistency_eval.py --base-url http://localhost:8080 --sample-docs 20 --bearer-token <INTERNAL_JWT>
python tools/storage_repair.py --base-url http://localhost:8080 --sample-docs 20 --force-reindex --bearer-token <INTERNAL_JWT>
python tools/rabbit_ingestion_eval.py --base-url http://localhost:8080 --bearer-token <INTERNAL_JWT>
```

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

## 16. 当前未完全收口事项

优先级 P0：

- [ ] 演示前跑一次 `mvnw.cmd -DskipTests package`。
- [ ] 确认 IDEA 环境变量：`OPENAI_API_KEY / MYSQL_PASSWORD / REDIS_PASSWORD / RABBITMQ_PASSWORD / MINIO_ACCESS_KEY / MINIO_SECRET_KEY`。
- [ ] 跑一次 retrieval / rag_chat / react_agent / harness / storage_consistency。
- [ ] 确认前端登录、发布商品、RAG Chat、内部 Agent 页面能用。
- [ ] 确认 GitHub 仓库无真实 API Key 和密码。

优先级 P1：

- [ ] 跑 `aiops_remediation_loop_eval.py`，确认 AIOps 修复闭环最终通过。
- [ ] 跑 `publish_feedback_eval.py`，确认发布助手反馈闭环最终通过。
- [ ] 跑 `knowledge_gap_eval.py`，确认知识缺口治理最终通过。
- [ ] 补 README 启动说明、架构图、演示数据导入说明。

优先级 P2：

- [~] 原生 Function Calling Loop（当前已完成受约束的结构化 Hybrid Planner）。
- [x] 有限动态 Replan。
- [ ] 语义级 compaction。
- [ ] Alertmanager 接入。
- [ ] 图像理解发布助手。
- [ ] L2 迁移 pgvector 或 Milvus。

## 17. 面试时可以诚实说明的边界

- 当前是混合 Planner：规则快路径 + 可选结构化 LLM 规划，不是完全自治的原生 Function Calling Loop。
- 当前 Graph/DAG 支持规则或结构化计划生成、拓扑并行和有限 Replan；写操作仍必须进入受控审批链路。
- AIOps 自动修复是白名单 Runbook，不是任意生产故障自愈。
- RAG confidence 是经验公式，不是概率校准模型。
- 评测以规则断言、关键词、Trace 和业务状态校验为主，不能完全替代人工质量评估。
- 三存储治理是最终一致性 + Repair，不是分布式事务。
- Runtime Context 有长度裁剪，但还不是完整上下文压缩系统。

## 18. 最终完成定义

项目真正收口需要同时满足：

- [ ] 商品发布形成“建议 -> 采纳 -> 发布 -> 风险结果 -> 指标”闭环。
- [ ] AIOps 形成“发现 -> 审批 -> 修复 -> 验证 -> 解决/转人工 -> MTTR”闭环。
- [ ] RAG 形成“拒答 -> 缺口 -> 补知识 -> 入库 -> 自动回归”闭环。
- [ ] 新 clone 可以按 README 完成后端启动、前端启动和基础演示。
- [ ] 所有核心指标可以追溯到固定数据集、Trace 表和评测报告。
