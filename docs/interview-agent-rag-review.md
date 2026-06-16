# RAG + Agent 项目面试复盘题

更新时间：2026-06-11

## 1. 项目整体定位

### Q1：这个项目到底解决什么问题？

回答要点：

- 面向二手电商/电商平台场景，解决发布风险判断、知识问答和内部排查辅助。
- 普通用户侧关注发布建议、风险解释、知识问答。
- 内部用户侧关注日志、指标、数据库、知识库的联合排查。
- 项目重点不是单纯接入大模型，而是把 RAG、Agent、权限、评测、Trace 和可靠性治理做成工程闭环。

推荐回答：

> 这个项目面向电商平台，主要做发布风险判断、知识问答和内部排查辅助。普通用户可以通过 RAG Chat 和发布助手获得有证据的发布建议；内部用户可以通过 Agent 调用知识库、日志、Prometheus 指标和只读数据库进行联合排查。我的重点不是只调 LLM API，而是把检索、生成、工具编排、权限隔离、评测和可靠性治理串成可回归的 AI 工程链路。

### Q2：怎么证明它不是一个 Demo？

回答要点：

- 有完整评测脚本：retrieval、rag_chat、react_agent、aiops、runbook、harness、memory、storage consistency、RabbitMQ ingestion。
- 有明确指标：Recall@K、MRR、Source Hit、Reject Accuracy、P95、case pass rate。
- 有 Trace、权限、Repair、Harness、故障注入和三存储一致性。

推荐回答：

> 我用多套 Python 评测脚本做回归，不只看主观回答效果。检索看 Recall@5 和 MRR，RAG Chat 看 Source Hit、拒答准确率和回答关键词，Agent 看工具选择、Trace 完整率和 Observation 使用率，Harness 看重试、超时、fallback 和快照恢复，知识库治理看 DB/ES/Milvus chunkId 一致性，异步入库还会检查消息任务状态和三存储写入结果。所以它更像一个可评测、可追踪、可治理的 AI 应用工程项目。

## 2. RAG 与混合检索

### Q3：向量是怎么来的？

回答要点：

- 分为文档向量和查询向量。
- 文档入库时先 chunk，再通过 Spring AI 调用 embedding model。
- 查询时用同一个 embedding model 生成 query vector。
- 文档向量和 query vector 必须处于同一个语义空间。
- 向量写入 Milvus，metadata 记录 docId/chunkId/riskLevel/source 等。

推荐回答：

> 向量分文档向量和查询向量。文档入库时，我先把知识文档切成 chunk，然后通过 Spring AI 调用 embedding model，把每个 chunk 转成固定维度向量，并和 docId、chunkId、metadata 一起写入 Milvus。用户查询时，也用同一个 embedding model 把问题转成 query vector，再到 Milvus 做 TopK 相似度检索。为了避免语义召回漏掉规则关键词，我又接了 ES BM25，最后用 RRF 融合两路结果。

### Q4：为什么不用纯向量或纯关键词？

回答要点：

- 纯关键词：同义词、口语化表达、上下文省略时容易漏召。
- 纯向量：风险规则、商品型号、发票、配件等关键词可能不够精确。
- 混合检索兼顾语义泛化和关键词精确匹配。

推荐回答：

> 业务规则问答里既有语义相似问题，也有很强的关键词约束。例如“全新未拆封、无发票”这种风险判断，关键词本身很关键。纯关键词遇到口语化问法容易漏召，纯向量又可能召回语义相近但规则不精确的内容。所以我用向量召回补语义，用 ES BM25 保留规则词和商品词的精确匹配，再用 RRF 做融合。

### Q5：RRF 为什么适合做融合？

回答要点：

- 向量分数和 BM25 分数尺度不同。
- 直接加权需要调参，稳定性差。
- RRF 基于排名融合，对不同检索器分数尺度不敏感。

推荐回答：

> 因为向量相似度和 BM25 分数不是同一个尺度，直接加权需要大量调参，而且不同 query 下不稳定。RRF 使用排名而不是原始分数来融合结果，能降低分数尺度差异的影响。对我这个场景来说，向量和关键词是两种异构检索器，用 RRF 更稳。

### Q6：Recall@5 和 MRR 分别说明什么？

回答要点：

- Recall@5：前 5 条有没有召回 gold doc。
- MRR：gold doc 排得越靠前越高。
- Recall 看能不能找得到，MRR 看排得好不好。

推荐回答：

> Recall@5 衡量目标文档是否出现在前 5 条结果里，主要看“能不能召回”。MRR 衡量目标文档首次出现的排名，排第一就是 1，排第二就是 1/2，所以它更关注“排得靠不靠前”。我的 hybrid 在 61 条 hard case 中 Recall@5 达到 100%，MRR 是 0.93。

## 3. RAG Chat 与回答质量

### Q7：RAG Chat 和 Agent 的区别是什么？

回答要点：

- RAG Chat：检索知识库后回答，主链路固定。
- Agent：判断问题需要什么工具，再调用工具，基于 Observation 回答。
- RAG 检索是 Agent 的一个工具，不等于 Agent。

推荐回答：

> RAG Chat 是固定链路：先检索知识库，再基于证据生成回答，适合普通用户的发布风险和知识问答。Agent 是工具编排链路，会根据问题选择知识库、日志、指标、数据库等工具，再基于 Observation 回答。也就是说，RAG 检索只是 Agent 可调用的一个工具。

### Q8：怎么避免模型编造价格、折扣或规则？

回答要点：

- Prompt 约束：只能基于证据回答。
- 价格问题没有证据时只能给方向性建议。
- 后处理识别无证据量化表达并替换。
- hard eval 专门覆盖价格类 case。

推荐回答：

> 我做了两层约束。第一层在 prompt 中要求模型只能基于证据回答，证据没有明确金额、百分比、折扣或价格区间时不能编造，只能给下调、扣减、参考近期成交价这类方向性建议。第二层是后处理，会识别回答中无证据支撑的量化价格表达并替换成保守说明。测试集里也加入了价格 hard case 来验证。

### Q9：证据不足时怎么拒答？

回答要点：

- 不只看 hits 是否为空。
- 综合 top1、top3 平均、关键词覆盖、多源命中、keyword hit 数。
- 低置信度时拒答并提示用户补充信息。

推荐回答：

> 我不是简单按是否有召回判断，而是结合 top1 分数、top3 平均分、关键词覆盖率、多源命中和 keyword hit 数做低置信度判断。证据不足时会明确说无法确定，并提示用户补充品类、型号、成色、维修史、配件、凭证等关键信息。

## 4. ReAct、Graph/DAG Agent 与工具编排

### Q10：ReAct Agent 在项目里做什么？

回答要点：

- 根据问题选择工具。
- 调用工具后得到 Observation。
- 基于 Observation 回答。
- 不是让 LLM 任意操作，而是受限工具编排。

推荐回答：

> ReAct 在项目里主要做工具编排：先理解问题，再选择工具，比如 knowledge_search、log_query、prometheus_query、mysql_read，然后把工具结果作为 Observation，最后基于 Observation 回答。它不是让 LLM 随便执行命令，而是通过工具白名单、权限和 Harness 控制执行边界。

### Q11：ReAct 和 Graph/DAG Agent 有什么区别？

回答要点：

- ReAct：线性串行工具调用。
- Graph/DAG：任务图调度，入度为 0 的节点可并行执行。
- 两者共用工具、Harness、权限和 Runtime Context。

推荐回答：

> 在我项目里，ReAct 是线性工具推理，适合少量工具或有明显先后关系的问题；Graph/DAG Agent 是任务图调度，会把工具调用拆成节点，入度为 0 的节点可以并行执行，再统一 merge Observation。它们不是两套完全独立系统，而是共用 AgentToolRegistry、ToolExecutionHarness、权限控制和 Runtime Context，只是调度方式不同。

### Q12：DAG 的目的只是为了提速吗？

回答要点：

- 提速是收益之一。
- 更重要的是节点状态、依赖、Trace、局部重试、可解释性。

推荐回答：

> 提速只是一个收益。DAG 更重要的是把复杂任务拆成节点，每个节点都有状态、耗时、Observation 和依赖关系。这样在 AIOps 场景里可以清楚展示查了哪些工具、哪个节点失败、是否可以局部重跑，也更适合后续做审批节点、局部重试和复杂编排。

### Q13：普通用户会调用 ReAct 吗？

回答要点：

- 普通用户主链路是发布助手和 RAG Chat。
- ReAct/Graph 主要用于内部排查和复杂诊断。
- USER 模式可调用部分公开工具，但内部工具会被拒绝。

推荐回答：

> 普通用户主链路不依赖 ReAct，主要用发布助手和 RAG Chat。ReAct/Graph 更多用于内部排查和复杂工具编排。普通用户即使调用 ReAct，也只能用 knowledge_search、profile 这类非内部工具，日志、Prometheus、MySQL 只读查询会被后端权限拒绝。

## 5. Harness 与可靠性治理

### Q14：Tool Execution Harness 是什么？

回答要点：

- 不是业务工具，是工具执行保护层。
- 支持超时、重试、Redis 快照恢复、fallback、指标记录。
- 防止工具失败拖垮 Agent 链路。

推荐回答：

> Harness 是工具执行保护层，不是新的业务工具。Agent 选择工具后，真正执行时会通过 Harness。它负责超时控制、失败重试、Redis 快照恢复、fallback 降级和指标记录。这样某个工具失败或超时，不会直接导致整个 Agent 链路不可用。

### Q15：Redis 快照恢复怎么工作？

回答要点：

- 工具成功执行后保存关键 Observation 快照。
- 后续同类请求失败时恢复最近可用结果。
- 更适合短期可复用的指标、日志、检索结果。

推荐回答：

> 工具成功执行后，Harness 会把关键 Observation 按 key 保存到 Redis，并设置 TTL。后续同类工具调用失败时，可以恢复最近一次成功的 Observation，返回 SNAPSHOT_RESTORED 状态。它适合 Prometheus、日志查询、检索这类短期可复用结果。

### Q16：三存储一致性为什么重要？

回答要点：

- MySQL 是 chunk 元数据源。
- ES 是关键词索引。
- Milvus 是向量索引。
- chunkId 不一致会导致召回、引用和修复都不可信。

推荐回答：

> RAG 的 chunk 同时存在三处：MySQL 保存 doc/chunk 元数据，ES 保存 BM25 索引，Milvus 保存向量索引。如果 chunkId 不一致，召回结果可能引用不到正确文档，keyword/vector 两路也没法对齐。所以我做了 DB/ES/Milvus 一致性检查、Repair API 和任务状态表，形成检查、修复、验证、报告闭环。

## 6. Memory 与 Runtime Context

### Q17：L1/L2/L3 记忆分别是什么？

回答要点：

- L1：短期最近会话。
- L2：长期语义记忆。
- L3：当前会话任务摘要。
- Profile：结构化偏好视图。

推荐回答：

> L1 是短期会话窗口，保存最近几轮问答；L2 是长期语义记忆，用 embedding 做召回；L3 是当前会话任务摘要，用 KV 结构记录当前商品、目标、缺失字段等；Profile 是从 L2 偏好中聚合出的结构化用户偏好，用于默认参数和表达风格。

### Q18：怎么避免历史偏好污染当前事实？

回答要点：

- Runtime Context 明确优先级。
- 当前用户输入和 Observation 优先于记忆。
- Profile 只能用于参数补全和表达风格。

推荐回答：

> 我在 Runtime Context 里明确了优先级：当前用户输入和工具 Observation 优先于 L3，L3 优先于 L2/Profile/Graph，历史记忆不能覆盖本轮明确事实。Profile 只能用于默认参数、排序和表达风格，不能覆盖本轮商品事实、RAG 证据和风险规则。

## 7. 权限、安全与 AIOps

### Q19：普通用户会不会查到数据库或日志？

回答要点：

- 不会。
- USER/INTERNAL/ADMIN 角色来自 MySQL role 和 JWT claim。
- Controller 和 Tool 层都有校验。

推荐回答：

> 不会。项目里把用户分为 USER、INTERNAL、ADMIN，role 写入 JWT。日志、Prometheus、MySQL 只读查询等工具是 internalOnly，普通 USER 调用会返回 DENIED 或 403。直接工具接口和 Agent 的 INTERNAL mode 都会做后端强校验。

### Q20：AIOps 是不是自动修复生产故障？

回答要点：

- 当前从“辅助排查与建议”升级到了低风险 Runbook 自动化 MVP。
- 仍不是任意生产命令自动执行。
- 支持白名单动作、dry-run、ADMIN 执行、幂等和执行后验证。

推荐回答：

> 当前项目已经从辅助排查扩展到低风险 Runbook 自动化 MVP，但不是任意生产命令自动执行。系统可以查 Prometheus、日志、MySQL 和知识库，生成诊断事件和建议动作；对于低风险动作，例如修复知识索引、批量 repair、标记事件解决，可以通过白名单 Runbook 执行。真正执行要求 ADMIN 权限，并支持 dry-run、idempotencyKey、执行后验证和 action 落库。

### Q21：如果要做自动修复，怎么保证安全？

回答要点：

- 白名单动作。
- dry-run。
- ADMIN 才能 execute。
- 幂等 key。
- 执行前检查、执行后验证、审计日志。

推荐回答：

> 我不会让 Agent 直接执行任意命令，而是做 Runbook 白名单。每个动作有风险等级、参数 schema、dry-run、幂等 key、执行前检查和执行后验证。低风险动作可以自动执行，中风险动作需要人工确认，高风险动作只给建议不执行。所有动作都写审计日志，失败时可以回滚或降级。

## 8. AIOps Runbook 自动修复

### Q22：你新增的自动修复链路具体做了什么？

回答要点：

- 新增 `GET /api/ai/aiops/runbooks` 查看白名单。
- 新增 `POST /api/ai/aiops/runbooks/execute` 执行或 dry-run。
- 首批动作：单文档 repair、批量 repair、标记事件解决。
- 执行结果写入 `aiops_action`。

推荐回答：

> 我新增了一层低风险 Runbook 自动修复能力。接口上提供白名单查询和执行接口，首批动作包括 `REPAIR_KNOWLEDGE_DOC`、`REPAIR_KNOWLEDGE_BATCH` 和 `MARK_EVENT_RESOLVED`。它不接受任意命令，只能执行预定义动作。执行后会把 actionCode、params、idempotencyKey、verificationStatus、result 等写入 `aiops_action`，方便审计和复盘。

### Q23：为什么自动修复只做知识索引 repair 和事件关闭？

回答要点：

- 这类动作风险低，影响范围明确。
- 已有 Repair API 和一致性验证可复用。
- 比“重启服务/执行 shell”更适合作为 MVP。

推荐回答：

> 我优先选择知识索引 repair 和事件关闭，是因为这类动作风险低、边界清晰，并且项目里已经有 DB/ES/Milvus 一致性检查、Repair API 和任务状态表，可以做到执行后验证。相比直接重启服务或执行 shell，它更适合作为自动修复 MVP，也更容易证明不会越权或误操作。

### Q24：dry-run 在自动修复里有什么用？

回答要点：

- 执行前校验 actionCode 和参数。
- 返回计划，不真正执行。
- INTERNAL 可 dry-run，ADMIN 才能 execute。

推荐回答：

> dry-run 用来做执行前校验和计划预览。系统会检查 actionCode 是否在白名单里、参数是否完整，但不会真正触发 repair 或修改事件状态。这样 INTERNAL 用户可以先看计划，真正执行必须 ADMIN 权限，降低误操作风险。

### Q25：idempotencyKey 解决什么问题？

回答要点：

- 防止重复点击、重试或网络抖动导致重复执行。
- 同一事件、同一动作、同一 key 已成功执行时返回 SKIPPED。
- 对自动修复非常关键。

推荐回答：

> idempotencyKey 主要解决重复执行问题。自动修复场景里，用户可能重复点击，接口也可能因为网络抖动重试。如果同一个 eventId、actionCode 和 idempotencyKey 已成功执行过，系统会返回 `SKIPPED / IDEMPOTENT_HIT`，不会重复触发 repair。这个机制对自动化动作很重要。

### Q26：怎么证明 Runbook 自动修复链路可用？

回答要点：

- 新增 `aiops_runbook_eval_dataset.json`。
- 新增 `tools/aiops_runbook_eval.py`。
- 覆盖白名单、dry-run、权限、执行、幂等、失败落库、事件关闭和未知动作拒绝。

推荐回答：

> 我单独加了一套 Runbook eval，目前有 12 条 case，覆盖白名单查询、INTERNAL dry-run、INTERNAL execute 被拒绝、ADMIN 单文档和批量 repair、重复 key 幂等、参数错误、事件关闭、USER 权限拒绝、未知动作拒绝，以及执行失败后 action 是否正确落库。首轮报告 11/12，唯一失败来自测试预期把失败动作误判为 executed=true，后端的 FAILED 状态和落库结果实际上正确；修正测试 Oracle 后需要重新回归。报告会统计执行、权限、幂等、验证、持久化和 P95。

## 9. AI 应用开发基础八股

### Q27：AI 应用开发和普通后端开发最大的区别是什么？

回答要点：

- 输出不完全确定。
- 需要评测、降级、约束和人工可解释。
- 不能只看接口是否返回 200。

推荐回答：

> 普通后端更偏确定性逻辑，AI 应用的输出有概率性和不稳定性，所以不能只看接口是否返回 200。需要补评测集、置信度判断、拒答策略、Prompt 约束、后处理、Trace 和降级。我的项目里就用 Recall、MRR、Source Hit、拒答准确率、P95 和 case pass rate 做回归。

### Q28：大模型应用里常见的降级策略有哪些？

回答要点：

- LLM 调用失败降级为基于 sources 的模板回答。
- 工具失败走 fallback 或 snapshot restore。
- 证据不足拒答。
- 超时返回结构化诊断，不阻塞主链路。

推荐回答：

> 常见降级包括：LLM 失败时用检索证据生成保守模板回答；工具失败时通过 Harness 走 fallback 或 Redis 快照恢复；证据不足时拒答并要求用户补充信息；AIOps 场景下结构化 Observation 已足够时可以跳过 LLM，用确定性总结降低 P95。

### Q29：Prompt 工程里你最关注什么？

回答要点：

- 任务边界。
- 证据约束。
- 输出格式。
- 反注入和冲突优先级。

推荐回答：

> 我最关注四点：第一是角色和任务边界，明确模型只能做什么；第二是证据约束，比如只能基于 RAG sources 和 Observation 回答；第三是输出格式，方便前端和评测解析；第四是冲突优先级，例如当前用户输入和工具 Observation 优先于历史记忆，避免 Profile 污染当前事实。

### Q30：Temperature、TopP 这些参数怎么理解？

回答要点：

- Temperature 控制随机性。
- TopP 控制采样候选范围。
- 工程应用通常低温更稳定。

推荐回答：

> Temperature 越高，输出随机性越强；TopP 是 nucleus sampling，控制从累计概率前 P 的 token 中采样。业务问答、风控、证据引用这类场景更需要稳定和忠实，所以一般用较低 temperature。创意文案可以适当提高，但也要有后处理和证据约束。

### Q31：什么是结构化输出，为什么重要？

回答要点：

- 让模型输出 JSON 或固定字段。
- 便于后端解析、评测和前端展示。
- 需要校验和 fallback。

推荐回答：

> 结构化输出就是要求模型按固定 JSON 或字段返回，比如风险等级、原因、证据、建议动作。它的重要性在于后端可以解析、前端可以稳定展示、评测脚本可以断言。实际工程里还要做 JSON 清洗、字段校验和 fallback，因为模型不一定每次都严格遵守格式。

### Q32：如何评估一个 AI 应用是否可靠？

回答要点：

- 不只评估模型回答。
- 检索、生成、工具、权限、延迟、失败率都要评估。
- 要有 hard case 和回归脚本。

推荐回答：

> 我会分层评估：检索看 Recall@K、MRR；生成看 Source Hit、拒答准确率、关键词覆盖和证据忠实度；Agent 看工具选择、Trace 完整率、Observation 使用率；可靠性看超时、重试、fallback、P95；权限看 USER/INTERNAL 越权是否被拒绝。项目里这些都有对应脚本。

### Q33：什么是 hallucination，工程上怎么缓解？

回答要点：

- 模型生成无事实依据内容。
- 用 RAG 证据、Prompt 约束、低置信拒答、后处理和引用缓解。

推荐回答：

> Hallucination 是模型生成没有事实依据或和证据冲突的内容。工程上可以通过 RAG 提供证据、Prompt 强约束“只能基于证据回答”、低置信度拒答、输出引用、后处理删除无证据量化内容来缓解。我的价格不编造规则就是针对这类问题做的。

### Q34：AI 应用为什么需要 Trace？

回答要点：

- 复盘模型和工具做了什么。
- 定位是检索错、工具错还是生成错。
- 支撑前端展示和评测。

推荐回答：

> Trace 可以记录一次请求里模型选择了什么工具、每个工具的输入输出、Observation、耗时、状态和最终回答。没有 Trace，只能看到最后答案，很难判断问题出在检索、工具、权限还是生成。我的项目里 ReAct/Graph trace 会落库，方便查询和 Eval Dashboard 展示。

## 10. Agent 开发基础八股

### Q35：什么是 Agent？

回答要点：

- 能根据目标进行推理、选择工具、执行动作、观察结果并继续决策。
- 工程上重点是受控工具调用，而不是完全自治。

推荐回答：

> Agent 可以理解为具备目标理解、工具选择、动作执行和观察反馈能力的系统。工程里我更倾向于受控 Agent，也就是工具白名单、权限隔离、Trace 和 Harness 都明确，而不是让模型完全自治地执行任意操作。

### Q36：Tool Calling 和 Agent 有什么区别？

回答要点：

- Tool Calling 是模型调用工具的能力。
- Agent 是围绕目标、工具、状态、记忆和调度形成的完整流程。

推荐回答：

> Tool Calling 是能力点，表示模型可以选择并调用某个函数或工具；Agent 是完整流程，包括问题理解、工具选择、参数构造、执行、Observation 解析、是否继续调用、最终回答和 Trace。Tool Calling 是 Agent 的组成部分，不等于完整 Agent。

### Q37：ReAct 的核心思想是什么？

回答要点：

- Reason + Act。
- 推理一步，行动一步，再观察。
- 适合工具调用链路。

推荐回答：

> ReAct 是 Reasoning + Acting，核心是模型先产生 thought 或计划，再调用工具，拿到 Observation 后继续推理或回答。它适合需要外部工具的场景，例如查知识库、查日志、查数据库，而不是让模型只凭参数知识回答。

### Q38：Plan-Execute-Replan 和 ReAct 有什么区别？

回答要点：

- ReAct 更细粒度，边想边做。
- Plan-Execute-Replan 先生成计划，再执行，再根据结果重规划。
- 复杂任务更适合显式计划。

推荐回答：

> ReAct 通常是一步推理、一步行动、一步观察，比较适合工具链不太长的场景。Plan-Execute-Replan 会先生成较完整的计划，再按步骤执行，根据执行结果重规划，适合复杂任务。我的项目当前更多是规则版 ReAct 和 DAG 调度，Plan-Execute-Replan 还属于后续增强方向。

### Q39：Agent 为什么容易不稳定？

回答要点：

- 工具选择错。
- 参数构造错。
- 工具失败或超时。
- Observation 没用好。
- 记忆污染或权限越界。

推荐回答：

> Agent 不稳定的原因很多：模型可能选错工具、构造错参数；工具本身可能失败或超时；模型可能没有正确使用 Observation；历史记忆可能污染当前事实；权限边界不清还可能导致越权调用。所以我做了工具白名单、权限校验、Harness、Runtime Context 优先级和评测断言。

### Q40：Agent 的记忆应该怎么设计？

回答要点：

- 短期上下文、长期记忆、任务状态要分开。
- 记忆要有召回、压缩、去噪和优先级。
- 当前输入和工具结果优先。

推荐回答：

> 我会把记忆拆开：L1 保存短期会话，L2 保存长期语义记忆，L3 保存当前任务状态，Profile 保存结构化偏好。注入时要有优先级，当前输入和 Observation 一定高于历史记忆。否则记忆越多，反而越容易污染回答。

### Q41：Agent 工具权限怎么控制？

回答要点：

- 工具标记 internalOnly。
- Controller 校验 mode 和 role。
- JWT 中带 role。
- 普通用户只能使用公开工具。

推荐回答：

> 我在工具层和接口层都做了权限控制。工具有 internalOnly 标记，Controller 会校验请求 mode 和 JWT role。普通 USER 只能调用公开知识工具，MySQL、日志、Prometheus、Harness debug、Runbook execute 等内部能力需要 INTERNAL 或 ADMIN，真正执行 Runbook 还要求 ADMIN。

### Q42：Agent 如何做可观测性？

回答要点：

- Trace run / step。
- 记录工具、输入、Observation、耗时、状态、metadata。
- Dashboard 聚合评测和失败趋势。

推荐回答：

> Agent 可观测性至少要记录 run 和 step。run 记录 traceId、问题、答案、状态、耗时、工具列表；step 记录 phase、tool、action、observation、elapsedMs、metadata。我的项目把 Trace 落到 PG，并做 Eval Dashboard 聚合评测报告和指标。

### Q43：Agent 和 Workflow 的区别是什么？

回答要点：

- Workflow 流程固定，更稳定。
- Agent 动态选择工具，更灵活。
- 高风险业务优先 Workflow，开放问题用 Agent。

推荐回答：

> Workflow 是固定流程，比如发布助手的优化、风控、定价、决策，稳定性强，适合高风险业务规则。Agent 是根据问题动态选择工具，更灵活，适合开放式咨询和内部排查。我项目里普通发布助手偏 Workflow，复杂排查偏 Agent。

### Q44：多 Agent 一定比单 Agent 好吗？

回答要点：

- 不一定。
- 多 Agent 增加通信和状态复杂度。
- 只有任务边界清晰、角色分工明确时才值得做。

推荐回答：

> 不一定。多 Agent 会增加通信、状态同步、冲突处理和成本。如果一个轻量 ReAct/DAG Agent 能解决，就没必要拆多 Agent。只有任务边界非常清楚，比如 planner、researcher、executor、critic 分工明确时，多 Agent 才可能带来收益。

## 11. 框架选择

### Q45：为什么不用 LangChain 或 LangGraph？

回答要点：

- 项目主技术栈是 Spring Boot + Spring AI。
- 需要和 Spring Security、MyBatis、Redis、Prometheus、WebSocket 深度集成。
- 自研轻量编排更方便控制权限、Trace、Harness 和评测。
- 后续复杂到多 Agent/动态图时可以再引入。

推荐回答：

> 我不是排斥 LangChain/LangGraph，而是当前项目后端主栈是 Spring Boot + Spring AI，需要和已有用户、商品、权限、数据库、WebSocket、Prometheus 深度集成。自研轻量 ReAct/DAG 编排层可以更好控制工具权限、Trace、Harness、Observation 格式和评测断言。当前 DAG 只是规则规划加拓扑调度，复杂度可控；如果后续发展到动态状态图、多 Agent 协作和人工审批节点，再引入 LangGraph 会更合适。

## 12. RabbitMQ 与可靠异步

### Q46：项目已经有 CompletableFuture，为什么还要 RabbitMQ？

回答要点：

- `CompletableFuture` 只解决进程内并发。
- 服务重启后，内存中的排队任务可能丢失。
- RabbitMQ 提供持久化队列、ACK、重试、DLQ 和跨实例消费。
- 入库任务耗时较长，不应阻塞 HTTP 请求。

推荐回答：

> 原来的 `CompletableFuture` 能让接口异步返回，但任务仍然绑定在单个 Spring Boot 进程上，服务重启后可能丢失，多实例之间也无法统一竞争任务。我把知识入库改成任务表加 RabbitMQ：API 先创建任务，再发送 taskId，消费者从数据库读取任务参数并执行。这样可以使用持久化消息、manual ACK、重试和死信队列，异步不再只是线程池并发，而是可恢复的后台任务。

### Q47：为什么选择 RabbitMQ，而不是 Kafka？

回答要点：

- 当前是任务队列和命令执行，不是高吞吐事件流。
- RabbitMQ 更适合 ACK、路由、延迟重试和 DLQ。
- Kafka 更适合事件日志、回放、多消费组和高吞吐。
- 不为了技术栈数量同时引入两个中间件。

推荐回答：

> 当前场景是“一个知识入库任务交给一个消费者可靠执行”，更接近工作队列。RabbitMQ 对 ACK、路由、延迟重试和死信处理支持直接，运维成本也更低。Kafka 更适合需要长期保留、事件回放、高吞吐和多个独立消费组的场景。项目现阶段没有这些需求，所以我选择 RabbitMQ，而不是为了架构看起来复杂同时加入 Kafka。

### Q48：RabbitMQ 入库消息怎么保证尽量不丢？

回答要点：

- durable exchange/queue。
- persistent message。
- publisher confirm 和 mandatory return。
- manual ACK，任务状态落库后再确认。
- 定时恢复 PENDING、陈旧 QUEUED 和卡死 RUNNING。

推荐回答：

> 发布端使用 durable exchange、durable queue、持久化消息和 publisher confirm，无法路由时通过 mandatory return 感知。消费端使用 manual ACK，只有任务执行和状态更新完成后才确认。数据库任务表是状态事实源，定时任务还会扫描未发布的 PENDING、长期 QUEUED 和卡死 RUNNING 并重新投递，所以即使发布确认失败或服务重启，也有补偿路径。

### Q49：消息重复消费怎么处理？

回答要点：

- 消息只携带 `taskId/retryCount/createdAt`。
- 消费前通过条件更新抢占任务。
- SUCCESS、RUNNING、DEAD_LETTERED 或旧 retry 消息直接跳过。
- 三存储写入仍需基于 chunkId 做幂等 upsert。

推荐回答：

> MQ 通常保证至少一次投递，因此不能假设消息只消费一次。我让消息只携带 taskId，消费前用 `taskId + retryCount + status` 条件更新把任务从 QUEUED 抢占为 RUNNING。已经成功、正在运行、进入死信或 retryCount 落后的消息会被跳过。索引写入也使用稳定 chunkId 和 upsert，避免重复消息生成重复数据。

### Q50：重试队列和死信队列是怎么设计的？

回答要点：

- 主队列执行任务。
- 失败后发布到带 TTL 的 retry queue。
- TTL 到期后通过 DLX 回到主队列。
- 默认重试 2 次，耗尽后进入 dead queue。
- 任务表同步记录 retryCount 和 DEAD_LETTERED。

推荐回答：

> 主队列消费失败后不会立即原地重试，而是把 retryCount 加一后发送到延迟重试队列。该队列设置 TTL，消息到期后通过死信交换机重新路由回主队列，避免持续失败时形成热循环。默认重试两次，耗尽后标记任务为 DEAD_LETTERED，并进入死信队列，后续由人工检查或修复后重放。

### Q51：数据库任务创建成功，但 MQ 发送失败怎么办？

回答要点：

- 当前使用任务表加恢复扫描实现 outbox-lite。
- 发布失败时任务仍留在 PENDING/QUEUED。
- Scheduler 定时重新投递。
- 更严格的方案是事务 Outbox + 独立 Publisher。

推荐回答：

> 数据库事务和 RabbitMQ 发布不是同一个本地事务，确实可能出现任务落库成功但消息发送失败。当前项目用 outbox-lite 处理：任务表是事实源，发送失败不会删除任务，恢复调度器会扫描 PENDING 和陈旧 QUEUED 再发布。这个方案适合当前规模；如果要求更强的投递保证，我会新增独立 outbox 表，在业务事务内同时写任务和事件，再由独立 publisher 投递并标记已发送。

### Q52：哪些操作适合异步，哪些不适合？

回答要点：

- 入库、向量化、reindex、repair、离线评测适合异步。
- 当前回答依赖的检索和工具 Observation 不适合丢到后台。
- 鉴权、风控和参数校验必须同步完成。
- 流式回答是异步 I/O，不等于后台任务队列。

推荐回答：

> 文档切片、Embedding、ES/Milvus 写入、reindex 和 repair 耗时较长，而且用户只需要先拿到 taskId，所以适合异步。当前问答依赖的 RAG 检索、工具 Observation、权限校验和风险判断必须在请求链路内完成，否则无法生成本轮回答。WebSocket 流式输出属于异步 I/O，但它和 RabbitMQ 后台任务是两个不同概念。

### Q53：怎么评测 RabbitMQ 异步入库链路？

回答要点：

- 提交异步任务并轮询任务状态。
- 检查 `asyncTask=true`、`taskType=INGEST`。
- 验证 DB/ES/Milvus 数量与 chunkId 一致。
- 统计提交和任务执行 P50/P95。
- 单测覆盖 ACK、retry 和 DLQ。

推荐回答：

> 我新增了 RabbitMQ ingestion eval。脚本通过 `async=true` 提交任务，轮询 `QUEUED/RUNNING/SUCCESS` 状态，并检查任务确实是异步 INGEST、DB/ES/Milvus 数量一致且至少存在一个 chunk，同时统计接口提交和后台任务执行的 P50/P95。当前 2 条 smoke case 已跑通，随后扩为 20 条正常任务集；消费者单测还覆盖成功 ACK、执行失败进入 retry queue 和重试耗尽进入 DLQ。

### Q54：20 条正常任务通过，能证明 MQ 可靠了吗？

回答要点：

- 只能证明正常链路的短期稳定性。
- 还需要重复投递、服务重启、MQ 中断、ES/Milvus 故障和积压测试。
- P95 在小样本下只能作为参考。

推荐回答：

> 不能。20 条正常任务主要验证消息提交、消费、状态流转和三存储写入在常规情况下稳定。可靠性还要看重复投递是否幂等、Spring Boot 重启后任务是否恢复、RabbitMQ 暂时不可用是否补投、ES/Milvus 故障是否重试并最终进入 DLQ，以及并发积压时的吞吐和延迟。小样本 P95 只能作为回归参考，不能当生产 SLA。

### Q55：测试失败后修改测试用例，是不是为了让结果通过？

回答要点：

- 必须区分实现缺陷和测试 Oracle 缺陷。
- 先用接口响应、数据库记录和业务语义交叉验证。
- 只有预期与需求不一致时才能修测试。
- 修改原因要留下记录并重新回归。

推荐回答：

> 修改测试本身不一定不合理，关键是不能看到失败就降低标准。我会先判断是实现错了，还是测试 Oracle 对业务语义理解错了。例如 Runbook 修复不存在文档时，后端正确返回 FAILED，并把 FAILED action 落库，但测试错误要求 executed=true，这属于测试预期错误。修正前要用接口结果、数据库记录和需求语义交叉验证，并在开发记录中说明原因；如果只是为了通过而删除断言或放宽阈值，那就是不合理的。

## 13. 简历表达建议

### 推荐项目定位

> 面向电商平台，搭建商品发布风控、知识问答与内部排查辅助系统，基于 RAG + Agent 实现可追溯的知识检索、发布建议和多工具诊断。

### 推荐三句话主线

1. 我不是只做 RAG，而是把 RAG 作为 Agent 的一个工具接入可评测的工具编排链路。
2. 我重点做了 Agent 工程化能力，包括权限隔离、Trace、Harness、低风险 Runbook、RabbitMQ 可靠异步和故障评测。
3. 我的指标不是只看模型回答主观好不好，而是用 Recall@K、MRR、Source Hit、拒答准确率、任务成功率、三存储一致率、P95 和 case pass rate 做回归。
