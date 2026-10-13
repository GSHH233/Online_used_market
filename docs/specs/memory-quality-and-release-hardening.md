# 记忆正确性、质量评测与发布收口 Spec

状态：`IN_PROGRESS`
创建日期：2026-09-18
规格版本：1.1（2026-09-18 复核补齐）
规格编写状态：`IN_PROGRESS`；MEM-01 至 MEM-06、EVAL-01/02 已验收，下一阶段进入 EVAL-03/04 质量证据补齐。
来源：[agent.md](../../agent.md) 第 16 节

本文规定下一阶段应该实现和如何验收。追踪矩阵记录实际进展；旧报告仅作为历史基线，不能自动填入新验收结果。

## 1. 背景与目标

当前已有 RAG、混合 Planner、有限 Replan、中心化多专家取证、Harness、Durable Runtime、受控修复与业务反馈闭环。本轮不重复建设这些模块，重点解决以下问题：

1. L1/L3 接口主要按 sessionId 访问，L3 新摘要把 sessionId 写入 userId；需要真实用户身份隔离。
2. 摘要恢复通过语义 Top1 后过滤类型，不能保证命中当前会话最新有效摘要。
3. L2 空 userId 缺少用户条件、混合类型先召回后过滤、最近 200 条候选限制和追加式写入，影响安全、纠正与召回。
4. 历史 Planner 在线 8/8 全部为规则路径、Replan 为 0；DeepEval 8/8 未启用 Judge；Token 8/8 是预览测试，均有未覆盖范围。
5. RAGAS Development v5 已有 PASS，较早的 Holdout v4 仍为 RISK；需要独立质量证据，不能合并成“全部通过”。

目标是获得可复现、可归因的正确性和质量证据。未知问题的生产泛化、任意生产故障自愈不在本轮承诺范围。

## 2. 范围、依赖与非目标

执行顺序：`MEM-01/02/03 -> MEM-04/05/06 -> EVAL-01/02/03/04 -> REL-01/02/03/04`。可先准备各阶段数据集与复现测试，但修复、质量冻结、发布签字必须按依赖完成。

| 范围 | 本轮处理 |
| --- | --- |
| L1/L2/L3/Profile/Graph | 修复身份、精确恢复、更新、筛选与失效传播；复用现有结构 |
| Agent/RAG | 增强实际路径覆盖、诊断质量、Judge 和成本对照，不默认重写执行框架 |
| Runtime/受控修复/知识生命周期 | 补充竞争、恢复、幂等和隔离回归，不新增任意写工具 |
| 前端 | 现有 USER/INTERNAL/ADMIN 页面回归；只修本轮接口变化带来的兼容问题，不新增记忆管理产品或演示中心 |
| 配置与数据 | 环境隔离、先备份、可预览迁移；不覆盖现有凭据或删除现有业务容器 |

明确不纳入：P2P、多框架迁移、通用语义 compaction、图像理解、未经测量的 L2 向量库迁移。简历对标中讨论的动态专家任务生成、专家多轮自治循环、跨专家原子共享预算账本应另立 Spec，不把这些建议隐含加入第 16 节。

复用约束：[Hybrid Planner](./hybrid-agent-planner-replan.md)、[Token MVP](./token-governance-mvp.md)、[多 Agent 持续发现](./aiops-continuous-detection-multi-agent.md)、[Durable Runtime](./durable-agent-runtime.md)、[受控修复](./controlled-repair-approval.md)。本 Spec 增加验收要求，不撤销旧 Spec 的权限、步数和审批限制。

## 3. 当前代码与修改范围

Java 类均在 `org.example.online_used_marketplace` 包下；下表明确子包，其余目录从仓库根计算：

| 模块 | 主要代码 | 预期修改 |
| --- | --- | --- |
| 身份和短期记忆 | `modules.ai.service.ConversationMemoryService`、`modules.ai.service.impl.InMemoryConversationMemoryServiceImpl` | 接口加入受信身份，复合缓存键，调用点迁移 |
| 摘要 | `modules.ai.service.SessionSummaryService`、`modules.ai.service.impl.InMemorySessionSummaryServiceImpl` | 精确恢复、版本控制与用户/会话隔离 |
| L2/Profile | `modules.ai.service.L2MemoryService`、`modules.ai.service.impl.L2MemoryServiceImpl`、`modules.ai.service.impl.UserProfileServiceImpl`、`modules.ai.entity.L2MemoryRecord` | 写入幂等、来源、状态、类型预过滤和候选策略 |
| 入口和组装 | `modules.ai.controller.L2MemoryDebugController`、`modules.ai.service.impl.RagChatServiceImpl`、`modules.ai.agent.runtime.AgentRuntimeContextBuilder` 及其调用方 | JWT 主体传播、调试接口隔离、上下文失效与审计 |
| 数据源 | `config/MySqlMapperScanConfig.java`、L2 Mapper、`src/main/resources/sql/` | 保留 MySQL 路由，补 MySQL 迁移与验证 |
| 评测 | `tools/memory_priority_eval.py`、`hybrid_planner_eval.py`、`deepeval_agent_eval.py`、`ragas_rag_eval.py`、`token_governance_eval.py` | 保留旧用法并补充本 Spec 的数据、模式、报告字段 |

实现前必须枚举所有调用方，包括 WebSocket、HTTP、Runtime 恢复和调试入口，禁止只改一个 Controller 而遗漏后台调用。表中简称对应类的实际位置应以仓库查找为准，不因此新建重复类。

## 4. 功能与数据契约

### 4.1 MEM-01：认证身份与访问范围

- `MEM-FR-001`：建立服务端构造的 MemoryScope，包含 ownerUserId、sessionId、scopeType；ownerUserId 来自已认证主体，不由请求正文、工具参数、模型输出决定。
- L1 和 L3 缓存键使用用户+会话的结构化复合键，不使用易碰撞的字符串直接拼接。所有更新、读取、清除使用相同键规则。
- 缺失身份时私有记忆读写失败关闭；调用方可返回明确错误或无记忆降级，但不能查全库或共享 anonymous/debug-user 记录。降级必须可观察。
- 长期偏好可以在同一用户不同会话复用；任务摘要只能在同一用户、同一会话恢复。跨会话事实仅在显式标记 USER 范围且与当前问题相关时使用。
- 异步任务使用入口校验后保存的身份，不能依赖已消失的 HTTP ThreadLocal，也不能信任后来消息中的角色字符串。
- 调试接口默认关闭；开启仍须校验角色。INTERNAL 仅可查看自身测试数据，ADMIN 跨用户操作仅允许显式测试命名空间并写审计；生产跨用户私有记忆访问不属于本轮。
- USER 请求中的 userId 若与认证主体不一致，应拒绝而非静默转查他人。权限失败沿用项目错误响应合同，HTTP/API 错误均不得被评测当成成功。

### 4.2 MEM-02：摘要精确恢复与版本竞争

- `MEM-FR-002`：增加按 ownerUserId+sessionId+SESSION_SUMMARY 读取当前有效摘要的方法，禁止使用 query embedding、importance 或语义 Top1 决定恢复版本。
- L3 的持久化是恢复来源，进程缓存是加速层；先成功持久化后更新缓存。写入失败不得报告“摘要已可靠保存”。
- 单个任务摘要维护递增 revision 和 expectedRevision，使用事务/CAS 防止并发更新丢失；冲突后重新读取，不能无条件覆盖。
- 提交给 LLM 的摘要提取任务携带目标 revision/轮次。旧任务迟到时不能覆盖新一轮已保存内容。
- 摘要记录当前商品/任务标识。明确切换商品时创建新任务状态或清空旧商品专有字段；长期偏好保留但本轮明确要求优先。
- 旧数据缺少可证明的真实 ownerUserId 时不自动恢复，进入迁移待确认状态，不通过 sessionId 猜测归属。

### 4.3 MEM-03：MySQL 数据源与迁移

`MEM-FR-003`：默认存储继续使用 MySQL，`mysqlSqlSessionTemplate` 路由不变；Memory Graph 继续使用现有 PG。旧 `l2_memory_pg16_init.sql` 标记为非默认历史脚本，不能让 MySQL 初始化器执行。

目标数据模型（新增/变更，尚未实现）：

| 存储 | 字段/约束 | 用途 |
| --- | --- | --- |
| `ai_l2_memory_record` 增量字段 | sourceEventId、idempotencyKey、payloadHash、sourceType、assertionStatus、scopeType、taskKey、slotKey、revision、status、supersedesId、validFrom、validUntil、deletedAt | 来源、幂等、版本与失效；时间统一按 UTC 存储/解释 |
| 同表唯一约束 | `(user_id, idempotency_key)`，幂等键非空且为稳定摘要 | 相同来源事件的重复投递只生成一份逻辑记录 |
| 新 `ai_memory_slot` | ownerUserId、scopeType、scopeKey、memoryType、slotKey、currentRecordId、revision | 摘要与单值偏好的当前指针；对应组合唯一，CAS 更新；不使用 MySQL 不支持的部分唯一索引 |
| 查询索引 | 用户/类型/状态/时间；用户/会话/类型/版本 | 身份和类型先过滤、摘要精确定位 |

scopeKey 对 USER 范围使用固定非空值，对 SESSION 范围使用 sessionId；所有受唯一约束字段避免 NULL 导致重复绕过。实际列采用项目 snake_case 命名，字段长度和外键策略在迁移实现时记录。

生命周期 status 为 ACTIVE/SUPERSEDED/DELETED/QUARANTINED；可信程度 assertionStatus 为 REPORTED/OBSERVED/INFERRED，不能把来源类别与生命周期混成一个字段。有效召回要求 ACTIVE、有效时间未过期、满足身份范围；INFERRED 不进入已确认事实区。SUPERSEDED/DELETED 不因旧消息重放回到 ACTIVE；重新表达同一偏好必须是新的授权事件。

首版迁移固定约束：owner/userId 最长 64 字符，session/scopeKey/sourceEventId/slotKey 最长 128 字符，超长拒绝而非静默截断；idempotencyKey/payloadHash 为 SHA-256 十六进制 64 字符，revision 为非负 BIGINT，枚举值服务端校验。身份键采用区分大小写的比较方式，避免 DB 与 JVM 归属判断不一致；复合唯一键长度须在实际 MySQL 版本验证。

currentRecordId 引用必须在事务内确认 owner/type/scope 一致，禁止指向其他用户记录。单值 slot 尚无记录时用唯一键处理并发创建；已存在时以 revision 条件更新，未更新任何行视为冲突。一次冲突只允许有限重读重试，不覆盖他人已提交内容。

迁移顺序：备份并验证可读 -> schema 增量 -> dry-run 分类统计 -> 可确定归属记录回填 -> 建唯一约束 -> 切换新读写。历史疑似错误归属记录标记 QUARANTINED，默认不召回；保留原记录及映射审计，禁止默认批量删除。

空库初始化、已有库升级、重复执行均须测试。优先复用现有初始化机制，启动检查不能自动执行破坏性迁移。回退必须配套身份安全开关，不能简单恢复到存在越权风险的旧读取逻辑。

### 4.4 MEM-04：幂等、纠正、删除与事实来源

- `MEM-FR-004`：写入携带稳定 sourceEventId（源消息/任务轮次），重试沿用原值。幂等键由用户、事件、记忆类型及稳定 slotKey 派生；同键同 payload 返回原记录，同键不同 payload 报冲突并审计。
- 单次请求可提取多个 slot，但生成顺序变化不能制造重复。客户端不能通过换序改变幂等身份。
- sourceType 区分 USER_EXPLICIT、TOOL_OBSERVED、MODEL_INFERRED、LEGACY_UNKNOWN。用户自述不等于客观验证；模型推断不能直接变成 CONFIRMED 事实。抽取器的判断不能升级来源可信度。
- 单值偏好同 slot 更新指针并使旧值 SUPERSEDED；事务内写新记录、变更旧状态和 CAS 指针。并发版本冲突不能由相关性分数决定输赢。
- “这次只给一句标题”属于本轮约束，不自动删除“平时喜欢详细回答”；“以后都简洁回答”才更新长期偏好。“不再喜欢某品牌”必须使旧偏好失效，不能仅另存一条否定文本。
- 删除使用 DELETED/tombstone，撤销当前指针，并使 Profile、L1/L3 缓存和关联 Graph 派生结果失效；旧事件重放不能复活删除记录。物理删除及保留期另行配置，不在测试中清空用户数据。
- 存储决策先于写入：不保存临时寒暄、凭据、模型猜测为长期事实；不把整段助手回答原样当成已确认 FACT。

### 4.5 MEM-05：有效召回与上下文组装

`MEM-FR-005`：检索顺序必须是：身份/范围 -> 有效状态/类型 -> 候选 -> 相关性门槛 -> 排序/去重 -> TopK -> 上下文预算。

- Profile 从有效 PREFERENCE 指针读取当前值，不再混合召回后按高分取最先出现的旧偏好。
- relevance 与 importance 分开输出；importance 仅在相关候选中排序，不能让完全无关的高重要度记忆通过门槛。词面 fallback 单独标记 retrievalMode/fallbackReason，不能假装 embedding 成功。
- 先在同一隔离用户的 200 和 1000 条记录上建立基线；本轮基准域内使用分页候选覆盖，不能只取最近 200 条。放在第 201/1000 条的有效 Gold 必须可召回。
- 数据超过配置候选上限时记录 candidateTruncated=true、候选数量和上限，不静默承诺全库召回；不在本轮强制迁移向量数据库。
- 过滤用户/范围必须在数据库候选查询阶段执行，不能先全库取候选再在内存去掉别人数据。
- Runtime 保留系统约束和本轮输入，按相关性、时效和证据覆盖取舍记忆。历史偏好不能覆盖明确本轮要求，用户说法也不能覆盖服务端权限与可信工具事实。
- 组装审计记录采用/排除的 memoryId、类型和理由，不保存敏感明文或模型隐藏思维链；输出只给有权访问者。

### 4.6 兼容接口与测试入口

公共 RAG/发布助手请求不新增必填 userId。MemoryScope、幂等事件与摘要 revision 从服务端链路传播；旧 session-only 服务方法全部迁移，不能保留绕过身份的兼容分支。

现有 `/api/ai/l2-memory/debug/write|query|profile` 保留路径，但需要遵守 4.1 的开关、权限和测试命名空间。`sourceEventId` 等测试输入只能用于隔离写入；错误身份不得自动退回 debug-user。旧 smoke/eval 脚本相应更新，并明确认证错误。

不新增公开“任意用户记忆管理”API。摘要恢复、纠正、删除首先通过服务级集成测试和真实会话链路验收；如必须增加故障注入入口，只在测试 profile 启用，默认配置拒绝注册。

### 4.7 服务接口与错误合同（拟新增/修改）

下列是实现目标，不是当前已可调用的方法。允许沿用项目命名，但不可省略身份、范围和并发约束。

| 服务 | 目标方法语义 | 返回与要求 |
| --- | --- | --- |
| ConversationMemoryService | getRecentMessages(scope, limit)、appendTurn(scope, sourceEventId, question, answer) | 仅当前 owner+session；同轮写入去重 |
| SessionSummaryService | getStructuredSummary(scope)、updateSummary(scope, sourceEventId, expectedRevision, summary) | 返回 revision、persistenceStatus；无摘要返回空而非他人默认值 |
| L2MemoryService | saveMemory(scope, command)、retrieve(scope, querySpec)、loadCurrentSummary(scope)、invalidate(scope, memoryId, expectedRevision) | 写入返回 recordId/revision/deduplicated；搜索类型和候选上限显式可追踪 |
| UserProfileService | buildProfile(scope) | 读取 USER 范围有效偏好；返回值对应最新 slot，不能靠语义相似度选版本 |

MemoryScope 由受信入口工厂构造，不接受直接 JSON 反序列化的 owner/role；异步恢复从已持久化、校验过的 run owner 获取。恢复/执行写操作时还须重新校验当前权限，不能只信任创建任务时的旧角色。

普通接口保留既有响应结构，新增 debug 字段仅向有权调用者提供；不要给 USER 返回原始私有 Prompt。内部搜索诊断至少包含候选数量/上限、candidateTruncated、类型、relevance、importance、fallbackReason、采用/排除 ID；异常中不回显凭据或其他用户数据。

| 情况 | 业务错误语义 | 重试/处理 |
| --- | --- | --- |
| 无认证 | UNAUTHORIZED（401） | 重新认证，禁止转成匿名共享记忆 |
| 错误 owner、角色不足 | FORBIDDEN（403） | 不自动重试、不降为查全库 |
| 超长键、非法类型/范围 | 参数错误（400） | 修正请求，不做字符串截断 |
| 同键不同 payload、CAS 版本冲突 | CONFLICT（409） | 重读状态；幂等冲突不能换随机键规避 |
| DB/embedding 不可用 | 明确 unavailable/degraded 状态 | 私有记忆不得泄漏；回答可无记忆降级，但写入不可伪装成功 |

错误码沿用 `ResultCode` 的实际枚举；沿用项目 HTTP/Result 包装方式，测试同时断言传输状态和 body.code。不要求本轮重写所有异常的 HTTP 映射。

### 4.8 测试开关、删除传播与故障边界

测试权限必须同时满足测试 profile、显式 memory debug 开关、角色授权、服务端测试账号白名单和本次 evalRunId 登记，不能仅凭客户端传来的 `eval-*` 前缀授权。新增开关默认 false，候选上限首版默认 1000、分页 200；测试/部署覆盖写入单独配置，不改变现有 yml。具体配置名与环境变量映射随首批实现同步到 `.env.example`，不得把候选数量阈值当作安全边界。

ADMIN 可以在已登记的合成账号之间验收隔离；不得靠这个入口读取真实 USER 的历史。普通 USER 的正常会话与自己的记忆不依赖 debug 开关。测试接口权限收紧造成旧脚本失败时应迁移测试身份，不能为了保持绿灯放开生产权限。

| 调用者 | 正常会话记忆 | debug query/profile | debug write/测试数据准备 |
| --- | --- | --- | --- |
| USER | 仅自身，服务端处理 | 拒绝 | 拒绝 |
| INTERNAL | 仅自身，服务端处理 | 仅自身且属于登记测试范围 | 拒绝；通过正常会话或管理员准备数据 |
| ADMIN | 自身正常会话；无默认他人读取权 | 仅已登记合成账号，审计 actor/owner/run | 仅已登记测试范围；不能借此修改真实用户记忆 |
| 未认证 | 不读写私有记忆 | 拒绝 | 拒绝 |

删除和纠正涉及 MySQL、PG、JVM/Redis，不能宣称跨存储事务：

1. MySQL 事务提交 tombstone/slot revision，并记录可重试的派生数据失效任务；复用现有可靠任务/事件机制，若无可复用存储再增量增加，不仅依赖进程内事件。
2. 注入前验证引用的 memoryId 仍属本用户且有效。MySQL 无法验证时相关派生记忆失败关闭；失效任务尚未完成也不能继续注入已删除内容。
3. Graph 派生记录必须保留 sourceMemoryId；单条边可有多个来源，只撤销被删除的支持，不无条件删掉其他有效来源。无法证明有效来源的旧派生结果不得注入。
4. 失效任务按事件 ID 幂等执行，更新缓存及 Graph；失败可重试并可审计，不把“源记录已删”混同“所有派生数据物理删除完成”。

每次上下文组装定义一个版本快照。删除提交后开始的请求不得注入旧值；已在途、已发送给外部模型的内容无法撤回，最终返回前若发现记忆版本改变，丢弃基于该失效内容的回答并无记忆重建一次或明确返回重试提示。测试与报告须说明这个边界，不宣称历史泄露可撤销。

## 5. 质量评测规范

### 5.1 MEM-06：五层记忆评测

`MEM-FR-006`：继续扩展 `tools/memory_priority_eval.py`，保留旧数据集的兼容性；新增 Development 48 条、Holdout 24 条，身份、更新、否定、重复、恢复、长历史六类分别至少 8/4 条。Graph 场景作为交叉标签纳入，相关/无关边都需 Gold；不能只有正例。

拟新增 `eval/datasets/memory_quality_development_dataset.json`、`memory_quality_holdout_dataset.json`。用例包含 caseId、requirementIds、用户/会话别名、事件序列、允许存储项、预期有效值、goldMemoryIds、forbiddenMemoryIds、expectedEdges。数据为合成信息，真实 ID 由测试运行映射，不固定依赖某个已有用户或 docId。

| 层 | 指标定义 | 本轮拟定验收门槛（非当前成绩） |
| --- | --- | --- |
| Store | Precision=正确存入/实际存入；Recall=正确存入/应该存入 | Precision >= 0.95，Recall >= 0.90；不该存的凭据不得进入长期记忆 |
| Recall | Recall@5、MRR；按标注相关 memoryId 集合计算 | Recall@5 >= 0.90，MRR >= 0.85；禁止项命中数=0 |
| Consolidation | 逻辑重复率、纠正后有效值、每事件有效记录增量、长度/检索质量曲线 | 20 次相同事件重放新增逻辑重复=0；明确纠正有效值全部正确；固定 Gold 下 1000 条 Recall@5 较 200 条下降不超过 5 个百分点 |
| Context assembly | 有用记忆数/实际注入记忆数，同时报告应注入项覆盖率 | 精度 >= 0.85、覆盖率 >= 0.90；身份泄漏和明确指令冲突=0 |
| Graph expansion | 正确边/生成边，并记录 Gold 边覆盖率 | Edge Precision >= 0.90、Recall >= 0.80；跨用户边和删除记录派生边=0 |

分母为 0 时标 N/A，不默认为 100%；在明确需要记忆/边的用例中返回空结果仍计漏召。熵、长度曲线是诊断数据，不把“熵越低越好”作为通过标准。身份/权限、确定性恢复和并发纠正是硬门禁，不能被平均分掩盖。

同硬件、相同并发与缓存模式下，对 200/1000 条候选分别报告服务 P50/P95 和外部 embedding 耗时。性能对照必须处理同等数量、同等范围的候选，不能拿旧版只扫描 200 条的低耗时要求完整扫描 1000 条也同样快。保留旧实现为产品历史基线，另外建立同覆盖率的正确性参考实现作为优化对照。

同覆盖率基线在优化前冻结；相对该基线 P95 增加不超过 20%，超出需性能评审，不能通过降低覆盖率达标。每个规模、并发和缓存条件至少 100 次观测，预热不计入，报告分位数算法；不足样本只能标探索性结果。所有新数值均为本项目首版验收目标，不是行业标准或已测成绩；调整必须在正式评测前修订 Spec/manifest 并保留旧值，曝光留出集后不允许按成绩降低门槛。

### 5.2 EVAL-01：Planner 与有限 Replan

扩展现有 Planner 脚本和测试，不新造无限循环。至少覆盖：规则直接查询、真实 LLM_STRUCTURED 成功、非法计划拒绝/回退、模型不可用、低证据补查、maxSteps 耗尽、Replan 上限、USER 拒绝、Graph 追加节点及恢复。

确定性单测使用可控模型/工具 stub；真实 API 测试单独标 live。非法输出故障注入只在测试环境，不要求真实模型“刚好出错”。

硬门禁：至少一个 live 用例确实走 LLM_STRUCTURED，至少一个用例在初次执行后出现 replanRounds>0 且不超过 2；新增工具不重复、不扩权、依赖合法。规则 fallback 不能计作 LLM 规划成功。模型无余额/不可用标 BLOCKED 或失败，不标全通过。

### 5.3 EVAL-02：Supervisor 质量与对照

新增诊断 Gold 开发集至少 24 条，覆盖证据一致、证据冲突、缺失、超时各 6 条；独立留出至少 12 条。记录可接受假设、不可断言结论、必要证据及应追问问题。不把“日志与指标都异常”机械标成同一根因。

拟新增 `tools/aiops_diagnosis_quality_eval.py` 及对应 `eval/datasets/aiops_diagnosis_quality_*_dataset.json`，调用现有协作入口/服务，复用 Trace，不新建可绕过审批的修复端点。

对照同模型、同证据快照、相同工具和输出预算的串行/并行执行；预热后至少三轮，交错顺序并记录缓存、并发及样本数。报告任务 P95、根因判断正确率、证据支持率、缺证据恰当保留判断率和实际 Token。

拟定质量门槛：根因正确率 >= 0.85、证据支持率 >= 0.90；引用 ID 存在性=100%，缺失/冲突场景不得无证据确定归因。并行方案质量较串行下降不得超过 2 个百分点；没有观测到速度/成本收益时如实记录，不强行声称并行更优。

### 5.4 EVAL-03：Judge 和新盲测

- RAGAS 检索门禁沿用原 Spec/数据集：ID Recall >= 0.90、Precision >= 0.55、case recall pass rate >= 0.90、请求错误=0；原有分类门槛不能静默取消。
- 新 Holdout 使用实现冻结后的全新未见题集，至少 30 条，覆盖多跳、改写、成色披露及拒答；不复用已分析的 Holdout v4 作为新的盲测证明。
- 正式盲测前固定 Judge 模型、提示、参考答案和阈值；抽取至少 10 条单独校准集做人机一致性检查，不用 Holdout 调 Judge。
- DeepEval 的工具/权限指标与任务完成质量分开；RAGAS faithful/relevant 等指标按各自 adapter 记录原始值、阈值、判定和失败原因。拟定人工校准后二元一致率 >= 0.80；未达标先阻止质量签字。
- LLM Judge 质量参考门槛为 faithfulness >= 0.90、answer relevancy >= 0.85；与检索、拒答、权限硬断言同时报告，不能替代业务验收。不可比较的 metric 不混算总分。
- 留出集首次运行后即视为已曝光；改实现后的再次运行标 regression，不继续称 blind。需要新的盲测时重新冻结和构造未见集。

### 5.5 EVAL-04：真实 Token 成本与质量

保留 `token_governance_eval.py` 无模型预览模式，新增明确 live 对照模式（尚未实现）。选同一固定的至少 20 条输入，覆盖短输入、超长记忆、长证据与失败重试；预算启用/对照组使用相同模型、温度、工具输出、并发和缓存策略。

对照组也必须有安全的输入/输出上限，不允许为了对照无限消费。两组均记录每次实际模型 attempt 的 usage；真正请求模型的重试可能收费，不能因相同业务请求只统计最后一次。若 SDK 内部重试不可见，报告计量不完整，不宣称精准总成本。

门禁：业务 trace 与可见 attempt 可关联；供应商 usage 与本地估算分列，缺失不得填 0；输入/输出预算断言通过；质量通过原门禁且较对照下降不超过 2 个百分点。仅对预先定义的超长输入子集报告节省率，不要求所有短输入都节省。缺少计价表时只报告 Token，不编造费用。

## 6. 验收追踪矩阵

编号直接映射 agent.md 第 16 节；新增 REL 编号对应其 16.4。测试名为待实现合同，不表示文件已存在。

| 来源 | 验收 ID | 用例/操作 | 通过条件 | 证据与初始状态 |
| --- | --- | --- | --- | --- |
| MEM-01 | MEM-AC-001 | 两用户相同 sessionId；空身份；伪造 userId；后台身份传播 | 全部零串读、无越权写入，拒绝可审计 | 双 USER、无/伪造 token、异步 Runtime 身份、对象隔离、PG 拒绝审计及 MySQL 角色降权重检均通过；`eval/reports/runtime_identity_eval_20260920-044231.json`、`runtime_role_revocation_eval_20260920-044342.json`；VERIFIED |
| MEM-02 | MEM-AC-002 | 混入高分 FACT、跨会话、进程重启、旧摘要提取迟到 | 精确恢复当前版本，旧结果不覆盖 | 重启、双会话、商品切换、高分 FACT、服务 CAS 单测和双连接 MySQL CAS 均通过；`eval/reports/memory_summary_cas_eval_20260920-043207.json`；VERIFIED |
| MEM-03 | MEM-AC-003 | 空库初始化、旧库升级、重复迁移、归属不明 dry-run | 数据保留、唯一约束生效、疑似记录不进入私有召回 | 隔离 MySQL 空库/旧库/重复执行/唯一约束全通过；项目库备份与分类后 1250 条全保留，35 ACTIVE、1215 QUARANTINED；在线 HTTP 证明 ACTIVE 可见且 QUARANTINED 不可见；VERIFIED |
| MEM-04 | MEM-AC-004 | 同事件 20 次并发投递；同键不同 payload；偏好纠正/删除 | 幂等、冲突拒绝、只保留新有效值，删除不复活 | 20 并发仅 1 CREATED/19 REPLAYED；payload 冲突 409；纠错 revision=2；删除 revision=3；Profile 不复活且 Graph 零孤儿数据；`eval/reports/memory_lifecycle_eval_20260921-021954.json`；VERIFIED |
| MEM-05 | MEM-AC-005 | 第 201/1000 条 Gold、无关高 importance、模型不可用 | 用户/类型先过滤，达到召回门槛，fallback 明确 | 201/1000 条规模、强制 fallback、真实 embedding 写入与 EMBEDDING 召回均通过；`eval/reports/memory_recall_eval_20260921-031816.json`；VERIFIED |
| MEM-06 | MEM-AC-006 | 48 条开发集、24 条留出集及五层统计 | 硬门禁全通过，质量指标达到 5.1 | 修正 Context 实测口径后，Development 48/48、独立 Holdout 24/24 通过；五层指标均为 1.0，冲突/越权/错误均为 0；VERIFIED |
| EVAL-01 | QUALITY-AC-001 | 开启 LLM 规划、低证据补查、非法计划、权限/预算耗尽 | 实际路径覆盖及服务端安全断言均通过 | 13/13 通过；4 条 `LLM_STRUCTURED`，2 条有限 Replan；候选工具约束、权限与预算门禁通过；`eval/reports/hybrid_planner_eval_live_20260921-final-candidate-scoped.json`；VERIFIED |
| EVAL-02 | QUALITY-AC-002 | 诊断 Gold；串并行同条件对照 | 达到 5.3；假设和证据可追溯，收益如实报告 | Development 24/24、独立 Holdout 12/12；根因/证据/引用/保留判断均为 1.0；并行 P95 31.9/37.5ms，串行 P95 77.6/87.0ms；确定性 Supervisor、Token=0；VERIFIED |
| EVAL-03 | QUALITY-AC-003 | Judge 校准、冻结、新 Holdout 盲测 | 校准和检索/生成门禁通过，失败保留 | 新 Holdout v6 已于首次执行前冻结；盲测 30 条、错误 0，但 ID Recall=0.7556、Precision=0.5439、case pass=0.6333，未达门禁；Judge 密钥未注入当前评测进程，Judge 尚未执行；`eval/reports/ragas_rag_holdout_v6_blind_deterministic_report.md`；RISK |
| EVAL-04 | QUALITY-AC-004 | Token 预览及至少 20 条真实对照 | usage 可追溯、缺失不掩盖、质量不退化 | attempt/usage 对账与成本报告；NOT_STARTED |
| 16.4 | REL-AC-001 | Runtime 中断、旧租约迟到、审批幂等、版本清理/回滚竞争 | 无旧结果覆盖、无重复副作用、无越权、失败转人工 | 隔离集成测试+Trace/audit；NOT_STARTED |
| 16.4 | REL-AC-002 | 单测、构建、API、三角色浏览器验收 | 顺序执行通过；USER 无内部入口和访问权 | 构建日志+截图/请求证据；NOT_STARTED |
| 16.4 | REL-AC-003 | 干净 clone、启动脚本、配置检查、五分钟清单 | 新环境可复现，无秘密入库，不依赖未跟踪源码 | clone commit+启动/演示记录；NOT_STARTED |
| 16.4 | REL-AC-004 | README/Spec/简历指标及公开报告归档 | 每个数字对应命名报告、数据集、时间和配置 | release manifest+脱敏证据索引；NOT_STARTED |

状态流转：`NOT_STARTED -> IMPLEMENTED_PENDING_EVAL -> VERIFIED`；失败为 `FAILED`，环境不可用为 `BLOCKED`，无关为有理由的 `NOT_APPLICABLE`。硬门禁不允许以 NOT_APPLICABLE 绕过；跳过项不能计入通过数。

每次实施在本节按下列格式追加一条验收记录，不覆盖先前失败：

| requirementId | caseIds | 实际代码/commit | 报告/Trace 路径 | executedAt | 验收人 | 状态/阻断原因 |
| --- | --- | --- | --- | --- | --- | --- |
| 待填（模板，不是验收记录） | 待填 | 待填 | 待填 | 未执行 | 待填 | NOT_STARTED |
| MEM-AC-001/002 | MQ-ISO-01、MQ-RESTORE-01、MQ-RACE-01 | 本地工作区，未提交；身份传播、对象权限/审计、角色重检、MySQL revision/CAS 与专项 live 脚本 | `eval/reports/memory_stage1_acceptance_20260919.md` 及其关联 JSON | 2026-09-20 04:43 CST | 本机自动化；待人工签收 | VERIFIED |
| MEM-AC-003 | MQ-MIGRATION-EMPTY、MQ-MIGRATION-UPGRADE、MQ-MIGRATION-REPEAT、MQ-LEGACY-QUARANTINE | MySQL 标准空库脚本、幂等升级初始化器、ACTIVE 召回门禁、备份后归属分类 | `eval/reports/memory_mysql_migration_eval_20260920-184435.json`、`memory_mysql_migration_eval_20260920-184457.json`、`memory_migration_live_eval_20260920-185618.json` | 2026-09-20 18:56 CST | 本机自动化 | VERIFIED |
| MEM-AC-004 | MQ-IDEMPOTENCY-01、MQ-CORRECTION-01、MQ-TOMBSTONE-01 | sourceEventId 幂等、槽位 current record、纠错 supersede、删除墓碑、Profile 当前值投影 | `eval/reports/memory_lifecycle_eval_20260921-021954.json`、`memory_stage2_acceptance_20260921.md` | 2026-09-21 02:19 CST | 本机自动化；测试数据已清理 | VERIFIED |
| MEM-AC-005 | MQ-RECALL-01、MQ-FALLBACK-01 | DB 侧 owner/type/status/scope 过滤；1000 条有界候选；相关性门槛与 importance 分离；Runtime 记录 adopted/excluded ID、模式和降级原因 | `eval/reports/memory_recall_eval_20260921-031816.json` | 2026-09-21 03:18 CST | 本机自动化；201/1000、lexical fault injection、真实 embedding 写入与正常语义召回均通过；测试数据已清理 | VERIFIED |
| MEM-AC-006 | MQ-SUITE-01 | 48 条 Development、24 条独立 Holdout、五层分子分母、身份/恢复硬门禁、图清理和可复现生成器 | 红灯基线 `memory_quality_development_report_20260921-105233.json`、最终 Development `memory_quality_development_report_20260921-111736.json`、修正口径 Holdout `memory_quality_holdout_report_20260921-111934.json`；冻结清单 `eval/datasets/memory_quality_freeze_manifest.json` | 2026-09-21 11:19 CST | Development 48/48、Holdout 24/24；Store/Recall/Context/Graph 指标均为 1.0，重复、冲突、越权及执行错误均为 0；首次 Holdout `105805` 因 Context 指标由召回结果推断而作废，数据集 hash 未变化；合成用户和记忆残留均为 0 | VERIFIED |
| QUALITY-AC-001 | Q-PLAN-01 | 13 条 live 用例覆盖规则路径、真实结构化 LLM、权限拒绝、预算耗尽、React/Graph 低证据补查；9 条 Planner 单测覆盖非法计划、模型不可用及候选工具约束 | `eval/reports/hybrid_planner_eval_live_20260921-final-candidate-scoped.md`、`hybrid_planner_eval_live_20260921-final-candidate-scoped.json`；完整 Java 回归 | 2026-09-21 15:37 CST | live 13/13；4 条 `LLM_STRUCTURED` 仅调用预期候选工具；React 与 Graph 各有 1 条 `replanRounds=1`；hardGateFailures 为空；Java 147 条中 146 通过、1 跳过 | VERIFIED |
| QUALITY-AC-002 | Q-SUP-01 | 24 条 Development、12 条首次 Holdout；一致/冲突/缺失/超时分层；同证据串并行各 3 轮交错执行 | `eval/reports/aiops_diagnosis_quality_development_report.json`、`aiops_diagnosis_quality_holdout_report.json`；`eval/datasets/aiops_diagnosis_quality_freeze_manifest.json` | 2026-09-21 15:56 CST | 两组均全通过；根因正确率、证据支持率、引用 ID 存在率、缺证据保留判断率均 1.0；并行 P95 31.9/37.5ms，串行 P95 77.6/87.0ms；确定性 Supervisor 未调用模型，实际 Token=0；Java 151 条中 149 通过、2 跳过 | VERIFIED |

MEM-FR-001~006 分别对应 MEM-AC-001~006；EVAL-01~04 对应 QUALITY-AC-001~004；REL-01~04 对应 REL-AC-001~004。辅助复现用例可一对多关联，但必须至少有一个明确主 requirementId。

## 7. 测试执行与报告合同

1. 创建隔离数据库/测试账号和唯一 evalRunId，记录基线快照；只允许清理该运行自己创建的数据。
2. 先编写能复现原缺陷的单元/集成测试。并发更新、唯一约束、排序和迁移必须对真实 MySQL 验证，不仅使用 mock/H2；Graph/Runtime 测试按实际 PG 验证。
3. 完成 MEM-01/02 后测试应用进程重启恢复；明确重启测试进程，不重启用户正在使用的服务或数据库容器。单纯清缓存不冒充进程崩溃测试。
4. 运行完整记忆开发集，记录 200/1000 条曲线；冻结实现后运行留出集。评测与正常链路的副作用不同必须标注，故障注入不对生产开放。
5. 执行 Planner/多专家/Judge/Token 在线测试；模型不可用时保留规则测试通过记录，但阻止相关 live 项签字。
6. 按 REL 顺序运行构建、真实 API、浏览器三角色和干净 clone 验收。浏览器验证发布及反馈、缺口治理、智能排查、运行详情、审批/拒绝/执行状态；隐藏菜单不能代替后端鉴权断言。

脚本增量合同：支持 base-url、bearer token 的环境变量输入、dataset、run-id、output-dir；默认保留现有脚本用法。新参数在开发时落实到 `--help` 后才发布执行命令，本 Spec 不给尚未实现的伪可执行命令。

新增报告必须自动创建目录，UTF-8 写入 Markdown 和 JSON；权限/网络失败逐条记录，依赖失败标 BLOCKED，不因 KeyError 丢失整份报告。失败/未完成返回非零退出码；实时打印 OK 不能代替最终报告落盘。

报告至少包含：requirementId、caseId、evalRunId、traceId/runId、operationId（如适用）、代码 commit及dirty快照标识、数据集 hash、模型与 Judge、温度、并发、缓存模式、时间窗、实际/估算 usage、fallback、指标分母、延迟口径、失败原因。Token/密码/真实私有记忆不得写入报告。

### 7.1 分批执行前置条件

| 批次 | 前置条件 | 允许使用 stub | 完成后交付 |
| --- | --- | --- | --- |
| A：身份/迁移/恢复 | 隔离 MySQL、两个测试用户、测试后端进程；涉及 Graph 时隔离 PG | 可以，用于排除模型随机性 | MEM-AC-001~004 复现与修复前后结果 |
| B：召回/组装 | A 通过、真实 embedding 可用；48/24 数据集与 Gold 冻结 | 单测可用，真实召回验收不可替代 | MEM-AC-005/006、200/1000 曲线 |
| C：Agent/RAG/成本 | 真实 chat/embedding/Judge 可用、内部角色、固定证据、费用上限确认 | 故障路径允许；live 主路径必须分开 | QUALITY-AC-001~004 对照及盲测报告 |
| D：发布回归 | A/B/C 通过、待发布代码冻结、隔离修复目标、浏览器可用 | 不替代真实权限/恢复验收 | REL-AC-001~004 和五分钟记录 |

在调用收费模型前，测试运行 manifest 必须填写供应商模型、允许的请求/输出总上限及人工确认的费用或 Token 上限；未填写不启动批量 live 测试。该约束用于评测运行器，不代表已实现多 Agent 运行时共享预算账本。

开发集与 Holdout 使用不同场景来源/实体组合/对话模板分组，不能把同题换名拆成两组。生成后审查 Gold，再冻结文件 hash；可由模型生成候选，但最终 Gold 不直接复制被测系统的输出。报告聚合同时给总体和分组结果，安全类失败独立展示。

### 7.2 最小可执行验收步骤

下面是实现后必须具备的验收过程；caseId 为本 Spec 预留，不表示脚本现已支持。样例数据为合成数据，准备过程也须遵守权限。

| caseId / requirementId | 准备与操作 | 必须核对的结果 |
| --- | --- | --- |
| MQ-ISO-01 / MEM-AC-001 | A/B 登录，各用 sessionId=same；分别保存不同商品和偏好，再各自提问 | L1/L3/召回/Profile/Graph/Prompt 中均无对方 ID 或内容 |
| MQ-ISO-02 / MEM-AC-001 | 不传 Token；USER 伪造 userId；INTERNAL 请求非测试用户；仅伪造 eval 前缀 | 未认证/越权明确失败，DB 无新增或修改，未调用全库记忆查询 |
| MQ-RESTORE-01 / MEM-AC-002 | A 的两个 session 保存不同摘要；插入高分 FACT；重启隔离后端，再读取摘要 | 分别恢复最新正确 revision；语义高分 FACT 不替代摘要 |
| MQ-RACE-01 / MEM-AC-002 | 用同步屏障让旧 LLM 提取先开始后结束，新轮先提交；并发更新同 slot | 迟到结果被拒绝/丢弃，新值不丢失；CAS 冲突有记录 |
| MQ-MIG-01 / MEM-AC-003 | 空库执行迁移；再对旧数据副本执行两次，含错误归属与重复来源 | schema/索引正确；原数据可核对，隔离记录不可召回；重跑不重复回填 |
| MQ-IDEM-01 / MEM-AC-004 | 20 个并发请求使用同 owner、sourceEventId、slot 和 payload；另发同键不同 payload | 一个逻辑事件/版本，重复返回相同 recordId；不同 payload 冲突，不产生额外有效值 |
| MQ-CORRECT-01 / MEM-AC-004 | 存“喜欢详细”；本轮要求一句标题；之后明确改为“以后简洁” | 一次性要求不改长期偏好，永久纠正才更新当前 slot；只读最新有效偏好 |
| MQ-DELETE-01 / MEM-AC-004 | 删除已有偏好；暂停 Graph 失效消费者；重放旧写入；再恢复消费者 | 删除提交后的新 Prompt 无旧内容，旧写入不复活；任务重试可完成；其他有效来源不被误删 |
| MQ-RECALL-01 / MEM-AC-005 | 构建 200/1000 记录集，把 Gold 放在最新 200 之外；加入无关 importance=0.8 记录 | 老 Gold 可进入相关 TopK；无关高重要度不能绕过门槛；范围/类型过滤可查 |
| MQ-FALLBACK-01 / MEM-AC-005 | 单测注入 embedding 超时；线上分别跑可用和不可用配置 | fallback 明确标注；不把降级组成绩当成真实 embedding 成绩 |
| MQ-SUITE-01 / MEM-AC-006 | 跑开发集并冻结实现；再首次跑独立记忆 Holdout | 五层分子分母、分组及安全门禁齐全；脚本产生报告且退出码符合结果 |
| Q-PLAN-01 / QUALITY-AC-001 | 开启实际 LLM Planner；复杂请求走模型；可控低证据触发补查；另测预算耗尽和 USER | live LLM 路径有证据，补查受限，权限拒绝不扩权，不能全部 RULE_FAST_PATH |
| Q-SUP-01 / QUALITY-AC-002 | 同一套 24 条证据分别串行/并行执行，覆盖缺失和冲突；跑独立留出 | 根因/引用/保留判断符合 Gold；质量和 P95/Token 不混为一个分数 |
| Q-JUDGE-01 / QUALITY-AC-003 | 独立校准集人工评判；锁定 Judge 后运行新 RAG Holdout | 参考答案、阈值、Judge 开关、失败类型完整；未校准不签字 |
| Q-TOKEN-01 / QUALITY-AC-004 | 相同 20 条输入对照预算开关，包含实际模型重试 | attempt 可关联，供应商 usage/估算分别统计；缺失不记零，质量门禁未放宽 |
| REL-RACE-01 / REL-AC-001 | 隔离 run 在节点成功落库后杀测试进程；恢复并释放旧 worker 迟到返回 | 已完成节点不重复产生副作用；旧租约结果不能覆盖；计划审批仍有效 |
| REL-VERSION-01 / REL-AC-001 | 同一测试 revision 并发发起清理/回滚；重复执行已审批计划；模拟验证失败 | 有效 ACTIVE 不被清理；最多一次生效；冲突可观察；失败正确转人工 |
| REL-UI-01 / REL-AC-002 | 三角色分别登录；USER 手工直访内部路由/API；INTERNAL 试审批；ADMIN 正常审批 | 前后端均按角色限制；拒绝不靠隐藏按钮；正常主链可演示 |
| REL-CLONE-01 / REL-AC-003、REL-AC-004 | 用待发布 commit 新 clone，独立环境启动并走五分钟清单 | 不依赖旧 target、未跟踪代码或本机明文秘密；报告/配置摘要与发布 commit 对齐 |

并发测试使用屏障、锁或测试注入点确定时序，不能只 sleep 后偶然通过。CLI/接口报错必须保留实际 code/message；测试预期的权限拒绝计通过，非预期 401/403 不得计成预期成功。

### 7.3 前端验收边界

沿用现有商品发布、RAG 对话及 `AgentInvestigationView.vue`、`AgentRunListView.vue`、`AgentRunDetailView.vue`，实现时定位现有角色路由和 API 模块，不新建同名替代页面。检查同浏览器退出 A 再登录 B 时清理本地会话/请求缓存；后端返回 401/403/409 时不能展示上个用户的历史结果。

管理端可以查看授权的 Trace/测试诊断，USER 只看自然语言结果和本人的业务数据。不为本轮验收向用户端公开完整组装 Prompt、其他用户记忆或敏感工具参数。记忆写入失败但回答成功时不得显示“已记住”，通过现有消息提示或状态字段明确表达。

## 8. 发布、风险与回退

- 修改配置前备份；本地秘密与公开示例分离。现有 yml 不为了测试被静默覆盖，公开扫描包含暂存区与待提交文件。
- 跨用户、空身份全库读取、未经审批写操作为阻断级问题；出现任意一例停止发布，不允许平均指标抵消。
- 旧数据不能确定归属是迁移阻断项，不使用“全部归给当前管理员”作为修复。
- 固定 API/执行预算不随质量实验放宽。若新检索延迟超过门槛，先分析候选/embedding 分段耗时，不直接换库掩盖问题。
- 回退新记忆功能时可临时关闭私有记忆注入，保留无记忆 RAG/Agent 可用；不得回退到跨用户共享缓存。迁移采用增量兼容，不自动 drop 新列或历史表。
- 不在此轮创建/删除业务容器；需要真实 Docker Runbook 验收时另行明确测试目标、备份和清理清单，只操作获准的隔离容器。

## 9. 完成定义与交付物

- [x] MEM-01 至 MEM-06 的实现、迁移、单测及隔离 HTTP 证据齐全。
- [ ] QUALITY-AC-001 至 004 分别通过；QUALITY-AC-001/002 已 VERIFIED，QUALITY-AC-003/004 待验收；没有把 stub、规则 fallback、估算 usage 当作真实模型成功。
- [ ] REL-AC-001 至 004 通过，失败与未覆盖项保留在报告中。
- [ ] 更新 agent.md 第 16 节及原有 Spec 的对应验收证据，不覆盖历史失败记录。
- [ ] 交付迁移/回退说明、数据集 manifest、评测脚本、MD/JSON 报告和三角色验收表。
- [ ] README/演示清单与实际代码一致；不扩大成“生产级未知问题全部通过”。

MEM-01/MEM-02 已完成双用户 WebSocket、重启恢复、双会话、商品切换、高分 FACT、异步 Runtime 身份、INTERNAL/ADMIN 对象权限、PG 拒绝审计、账号降权重检、MySQL slot revision/CAS 及双连接竞争验收。MEM-03 已完成 MySQL 空库初始化、已有库幂等升级、唯一约束、旧 owner 分类和在线召回门禁；项目库保留 1250/1250 条并创建备份表，不明归属保持 QUARANTINED 且无法被 HTTP 召回。MEM-04 已完成 sourceEventId 幂等、同槽位并发串行化、payload 冲突拒绝、偏好纠错 supersede、删除墓碑和 Profile 当前值投影。MEM-05 已将候选上限从固定最近 200 条扩展为可审计的有界 1000 条，分离 relevance 与 importance，并向 Runtime 暴露无明文的采用/排除审计；201/1000 条、lexical fallback 与真实 embedding 召回均通过。MEM-06 已用真实 `AgentRuntimeContextBuilder` 完成五层评测，Development 48/48、Holdout 24/24 通过。EVAL-01 已用 live 模型验证 4 条结构化规划、React/Graph 各 1 条有限 Replan、候选工具与权限预算门禁。EVAL-02 已完成 24 条开发集与 12 条独立 Holdout 的结构化 Supervisor 质量和串并行同证据对照。MEM-01~06、EVAL-01/02 均为 VERIFIED；下一步进入 EVAL-03/04，不提前宣称发布收口。

## 10. 规格复核记录

2026-09-18，版本 1.1：补齐服务签名、错误语义、角色权限矩阵、生命周期与可信程度分离、CAS 和幂等约束、跨 MySQL/PG/缓存失效流程、测试白名单、验收操作步骤及可填写证据模板。修正原性能对照中候选覆盖率不同导致比较不公平的问题。

文档编写检查（仅检查规格，不代表实现）：

- [x] 覆盖 agent.md 第 16 节全部 MEM/EVAL 项及发布收口项。
- [x] 保留原有框架、权限与审批边界，没有把对标简历建议自动扩入范围。
- [x] 分清现有代码、目标接口、拟新增数据/脚本与历史测试证据。
- [x] 指定各阶段依赖、数据迁移、失败处理、回退和测试安全范围。
- [x] 提供 14 项验收追踪、19 组代表性操作及报告/签字合同。
- [ ] 业务实现与在线验收：尚未开始，按第 6 节逐项推进。
