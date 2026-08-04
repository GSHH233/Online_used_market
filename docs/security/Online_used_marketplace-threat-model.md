# Online_used_marketplace Security Threat Model

## Executive summary

本模型重点覆盖知识文档上传、RAG Prompt Injection、Agent 工具调用与受控修复链路。项目已经具备不少正确的安全基础：JWT 角色模型、内部工具二次鉴权、规则驱动的工具规划、只读工具、受控操作白名单、审批状态机、执行后验证、审计记录和数据库幂等约束。

当前最需要优先处理的风险不是“LLM 会不会自己执行修复”，而是外围边界过宽：

1. RAG WebSocket 无需认证、允许任意 Origin，并把完整 Prompt 和记忆调试信息放入最终响应。
2. L2/Memory Graph 调试接口只要求登录，普通用户可指定任意 `userId` 读取或写入记忆，形成越权与持久化 Prompt Injection。
3. 通用文件上传接口公开且不校验真实文件类型，可被用于匿名存储滥用或托管主动内容。
4. 若部署时没有覆盖仓库中的默认 JWT 密钥，攻击者可自行签发 ADMIN Token。

按当前“本地面试演示、非公网、演示数据”的假设，现实暴露面较小；一旦公开部署或接入真实用户数据，上述四项应作为上线阻断项。

## Scope and assumptions

**范围内**

- Spring Boot API、Spring Security/JWT 与 WebSocket。
- Markdown、PDF、Word 知识文档上传、解析、MinIO 原文存储和异步入库。
- RAG 检索、Query Rewrite、会话记忆、Prompt 组装、模型调用与回答回传。
- ReAct/Graph Agent、工具注册表、Harness、日志/Prometheus/MySQL/向量工具。
- Controlled Operation、AIOps Runbook、Repair、审批、验证、转人工和审计表。
- 与上传及 AI 链路直接相关的 MySQL、PostgreSQL、Redis、RabbitMQ、ES、Milvus 和 MinIO。

**范围外**

- Docker/虚拟机宿主机、云账号和模型供应商本身的入侵。
- 浏览器、操作系统和中间件未修补漏洞的完整供应链审计。
- 支付、订单争议和传统电商业务风控。

**已采用假设**

- 当前主要用途是本地面试演示，服务默认不暴露到公网。
- 数据库中主要是演示商品、测试知识和测试记忆，不包含生产级敏感数据。
- `USER` 是普通用户，`INTERNAL` 可诊断和创建计划，`ADMIN` 可审批及执行受控操作。
- 若未来公网部署、多人共用或存放真实日志/记忆，本报告按更高影响等级处理。

**待确认问题**

- MinIO 两个 Bucket 是否都是私有策略，是否经独立下载代理返回。
- 生产是否有 API Gateway、TLS、WAF、限流和 JWT 密钥管理。
- 知识文档是否来自受信内部人员，是否存在第三方批量导入。
- INTERNAL 与 ADMIN 是否可能属于同一账号，是否要求双人审批。

## System model

### Primary components

- Vue 3 客户端：商城、发布助手、RAG Chat、内部调查与审批界面。
- Spring Security/JWT：认证请求并从 Token Claim 构造 `USER/INTERNAL/ADMIN` 权限。
- Knowledge Ingestion：接收 MD/PDF/DOC/DOCX，Tika 检测 MIME，解析并写入知识库。
- RAG：Query Rewrite、Hybrid/RRF 检索、记忆组装、LLM 生成与证据后处理。
- Agent：规则 Planner 选择工具，ReAct 串行或 Graph/DAG 并行执行，LLM 汇总 Observation。
- Tool Harness：超时、重试、Redis 快照、fallback 和指标。
- Controlled Operation：计划、审批、执行、验证、幂等和转人工。
- 数据层：MySQL 业务/Chunk，PostgreSQL 可观测与 Memory Graph，ES 关键词，Milvus 向量，Redis 快照，MinIO 文件，RabbitMQ 异步任务。

### Data flows and trust boundaries

1. 浏览器跨越公网/本机边界访问 HTTP API 和 WebSocket。
2. 上传文件从不可信输入进入解析器，再进入 MinIO、数据库、ES 和 Milvus。
3. 用户问题、检索 Chunk、会话记忆和工具 Observation 跨越“非指令数据”到“LLM Prompt”边界。
4. Agent 从自然语言进入规则 Planner，再跨越工具权限边界访问日志、指标和数据库。
5. INTERNAL 创建计划，ADMIN 审批后跨越写操作边界执行 Repair/Runbook。
6. RabbitMQ、Redis 和数据库承担异步、快照、幂等和审计状态，均应视为高信任内部组件。

#### Diagram

```mermaid
flowchart LR
  U["USER / 浏览器"] -->|HTTP, WebSocket| API["Spring Boot API"]
  I["INTERNAL / ADMIN"] -->|JWT| API
  API --> AUTH["JWT + Role Guard"]
  API --> ING["Knowledge Ingestion"]
  ING --> PARSER["MD / PDF / Word Parser"]
  PARSER --> OBJ["MinIO 原文"]
  PARSER --> DB["MySQL Chunk / Doc"]
  DB --> ES["Elasticsearch"]
  DB --> MV["Milvus"]
  U --> RAG["RAG Chat"]
  DB --> RAG
  ES --> RAG
  MV --> RAG
  MEM["L2 / Profile / Memory Graph"] --> RAG
  RAG --> LLM["外部 LLM / Embedding"]
  I --> AGENT["ReAct / Graph Agent"]
  AGENT --> HARNESS["Tool Harness"]
  HARNESS --> TOOLS["Log / Prometheus / SQL / Vector"]
  I --> PLAN["Controlled Operation Plan"]
  PLAN --> APPROVE["ADMIN Approval"]
  APPROVE --> RUNBOOK["Runbook Execute + Verify"]
  RUNBOOK --> DB
  RUNBOOK --> AUDIT["PostgreSQL Audit / MTTR"]
```

## Assets and security objectives

| 资产 | 安全目标 |
|---|---|
| JWT 密钥与角色 | 防止伪造、越权、降权后继续使用旧权限 |
| 用户记忆/Profile | 用户间隔离，防止读取、覆盖和持久化污染 |
| 知识文档与 Chunk | 来源可信、内容完整、版本可追踪、恶意内容不可升级为指令 |
| 日志、指标、数据库结果 | 仅 INTERNAL 可见，结果最小化和脱敏 |
| Agent 工具能力 | 只能调用注册工具，参数受约束，USER 不得使用内部工具 |
| Repair/Runbook | 白名单、审批、幂等、可验证、可审计、失败转人工 |
| Prompt 与 Trace | 不向普通用户泄露系统规则、其他用户记忆或内部 Observation |
| MinIO/ES/Milvus/DB | 数据一致、不可被匿名写满、不可绕过应用权限直接访问 |
| 模型额度与服务容量 | 防止匿名调用、上传和重试造成资源耗尽 |

## Attacker model

### Capabilities

- 未登录的远程访问者可访问公开 HTTP 路由并建立当前 WebSocket。
- 普通注册用户可构造任意 API 请求，不受前端菜单和路由守卫限制。
- 恶意 INTERNAL 用户可上传知识文档、运行只读诊断工具和创建操作计划。
- 攻击者可在文档、问题、日志、数据库字段或记忆中植入 Prompt Injection 文本。
- 攻击者可并发调用审批/执行接口，尝试状态竞争或资源耗尽。
- 若默认 JWT 密钥未覆盖，攻击者可离线构造任意角色 Token。

### Non-capabilities

- 默认不假设攻击者已取得宿主机、数据库、Redis、RabbitMQ 或 MinIO 管理凭证。
- 默认不假设攻击者能篡改已构建的服务端代码。
- 普通用户无法直接让规则 Planner 选择内部工具；INTERNAL 模式还会检查真实 JWT 角色。
- RAG Chat 本身不执行 Agent 工具，文档 Prompt Injection 不能直接触发 Repair。

## Entry points and attack surfaces

| 入口 | 信任级别 | 主要风险 | 代码证据 |
|---|---|---|---|
| `/api/ai/knowledge/docs/upload` | INTERNAL | 恶意解析、知识投毒、资源耗尽 | `KnowledgeDocController.java:53-64` |
| `/api/ai/knowledge/docs` | INTERNAL | 绕过文件解析直接写入恶意知识 | `KnowledgeDocController.java:67-70` |
| `/ws/ai/rag-chat` | 匿名 | 额度耗尽、Prompt Injection、调试信息泄露 | `SecurityConfig.java:47-49`、`WebSocketConfig.java:24-27` |
| `/api/ai/rag-chat/eval` | 已登录用户 | 批量模型调用、Prompt/Memory Debug 泄露 | `RagChatEvalController.java:27-36` |
| `/api/ai/l2-memory/debug/*` | 已登录用户 | 任意用户记忆读写、持久化 Prompt Injection | `L2MemoryDebugController.java:22-55` |
| `/api/ai/memory-graph/debug/*` | 已登录用户 | 任意用户图记忆读写/枚举 | `MemoryGraphDebugController.java:27-49` |
| `/api/ai/agent/react-chat`、`graph-chat` | 已登录用户 | 工具误用、成本耗尽、Observation 注入 | `ReactAgentController.java:38-69` |
| `/api/ai/tools/mysql/read` | INTERNAL | 白名单绕过、敏感数据读取、重查询 | `SqlReadToolController.java:30-33` |
| `/api/ai/operations/*` | INTERNAL/ADMIN | 未授权修复、审批竞争、重复执行 | `ControlledOperationController.java:26-63` |
| `/api/ai/aiops/runbooks/execute` | INTERNAL/ADMIN | 绕过受控计划直接执行 | `AiopsRunbookController.java:27-36` |
| `/api/files/upload` | 匿名 | 任意文件托管、存储耗尽、主动内容 | `SecurityConfig.java:47`、`FileServiceImpl.java:34-78` |
| `/actuator/*`、Swagger | 匿名 | 端点与运行指标枚举 | `SecurityConfig.java:38-53` |

## Top abuse paths

1. **伪造 ADMIN Token并执行 Repair**
   - 服务以仓库默认 JWT 密钥启动。
   - 攻击者使用已知默认值签发带 `role=ADMIN` 的 Token。
   - 创建/审批/执行受控操作，或暂停、恢复、重放 Runtime。
   - 结果是知识索引被重建、事件被错误标记、审计可信度丢失。

2. **匿名 WebSocket 获取 Prompt 与记忆**
   - 任意网站从浏览器连接允许 `*` Origin 的 `/ws/ai/rag-chat`。
   - 发起问题并等待 `CHAT_DONE`。
   - 响应对象包含 `promptDebug` 和 `memoryAssemblyDebug`，可暴露召回证据及会话记忆。
   - 并发连接还可消耗模型额度和检索资源。

3. **普通用户污染其他用户长期记忆**
   - 登录普通账号。
   - 调用 L2 Debug Write 或 Memory Graph Extract，自行指定受害者 `userId`。
   - 写入“忽略规则、泄露上下文”等持久化内容。
   - 后续 RAG/Agent 上下文组装将污染内容带入 Prompt，影响回答或诱导信息泄露。

4. **恶意知识文档形成间接 Prompt Injection**
   - INTERNAL 上传看似正常的 DOCX/PDF，其中包含“忽略系统指令”等文本。
   - 文档自动入库并被 Hybrid Retrieval 召回。
   - Chunk 与用户问题直接拼入同一个用户 Prompt，未以结构化不可信数据隔离。
   - 模型可能遵循文档指令，输出错误风险结论或泄露上下文。
   - 由于 Agent Planner 是规则驱动，当前不会因此新增高权限工具调用，这是重要的影响限制。

5. **匿名通用上传滥用 MinIO**
   - 未登录攻击者向 `/api/files/upload` 上传 HTML/SVG/超大或大量文件。
   - 服务信任声明的 Content-Type 并返回可访问 URL。
   - 可造成存储耗尽；若 Bucket/响应头允许主动内容，还可能形成钓鱼或存储型脚本风险。

6. **INTERNAL 绕过 SQL 表白名单**
   - 构造以 `SELECT` 开头的查询，真实目标为非白名单表。
   - 在注释、字符串或子查询中加入白名单表名，满足简单的 `contains` 判断。
   - 利用 `UNION`、函数或复杂查询读取更多数据或消耗数据库资源。

7. **审批状态竞争和职责未分离**
   - 两个 ADMIN 并发审批/拒绝同一计划。
   - 代码先读取状态，再执行不带旧状态条件的 UPDATE，可能同时写入互相矛盾的生命周期记录。
   - 同一 ADMIN 也可创建、审批并执行 MEDIUM 风险操作，缺少生产环境双人原则。

## Threat model table

| ID | Threat scenario | Preconditions | Impacted assets | Existing controls | Gaps | Recommended mitigations | Detection | Likelihood | Impact | Priority |
|---|---|---|---|---|---|---|---|---|---|---|
| TM-01 | 使用默认 JWT 密钥伪造 ADMIN | 未设置 `JWT_SECRET` | 所有管理与修复能力 | HMAC 长度校验、Token 过期 | 默认值公开且服务可正常启动 | 生产 Profile 启动时拒绝默认/空密钥；使用 Secret Manager；支持 `kid`、轮换和撤销 | 记录签发主体、异常 ADMIN 行为、密钥指纹 | High | High | P0 |
| TM-02 | 匿名跨域 WebSocket 调用并读取 Prompt/记忆 Debug | 可访问 8080 | 模型额度、Prompt、记忆、证据 | 问题长度 500、TopK 最大 20 | `/ws/**` permitAll、Origin `*`、无用户绑定；`WsChatDoneVO` 含 Debug | WebSocket Handshake 校验 JWT 与 Origin；USER 响应 DTO 移除 Debug；限流和并发配额 | WS 连接数、用户调用量、模型 Token、异常 Origin | High | High | P0 |
| TM-03 | 普通用户跨用户读写 L2/Graph Memory | 任意已登录账号 | 用户 Profile、长期记忆、回答完整性 | 全局要求 authenticated | Debug Controller 无角色检查且接受任意 `userId` | 默认禁用 Debug Controller；至少 INTERNAL；业务接口从 Principal 派生 userId；对象级授权 | 记录 actorUserId/targetUserId，不一致告警 | High | High | P0 |
| TM-04 | 匿名上传任意文件到 MinIO | 无需登录 | 存储容量、用户浏览器、对象存储信誉 | 全局 multipart 20MB | 路由公开；不校验 MIME/扩展名；无文件数量配额 | 改为 authenticated；仅允许图片；magic-byte 检测、重编码、私有 Bucket、签名下载、用户配额、恶意文件扫描 | 上传速率、类型分布、Bucket 增长、扫描结果 | High | High | P0 |
| TM-05 | DOCX/PDF 压缩炸弹或解析器 DoS | INTERNAL 上传权限 | JVM 可用性、解析线程池 | 20MB、页数/文本上限、30s Future 超时、2 线程池 | 解析在主 JVM；取消不保证底层库停止；无解压比/对象数/内存限制 | 隔离解析 Worker/容器；CPU/内存/墙钟限制；压缩比和嵌套对象限制；队列背压；杀死超时 Worker | 解析耗时、堆内存、线程存活、拒绝原因 | Medium | High | P1 |
| TM-06 | 文档或记忆中的间接 Prompt Injection 改变 RAG 回答 | 恶意文档/记忆被召回 | 风险建议完整性、Prompt 隐私 | System Prompt、证据不足拒答、量化后处理、来源元数据 | Evidence/Memory 与指令未结构化隔离；未检测注入；上传后立即入库 | 文档 `QUARANTINED→REVIEWED→ACTIVE`；检索仅 ACTIVE；Evidence 用独立消息/JSON 边界并声明不可信；注入分类器；敏感输出过滤；建立攻击评测集 | 注入命中率、越权回答率、引用忠实度、异常指令模式 | Medium | High | P0 |
| TM-07 | Tool Observation 中恶意文本操纵最终 Agent 回答 | INTERNAL 工具返回被污染数据 | 内部诊断可信度、日志隐私 | 工具选择和参数由规则生成；内部工具按模式/角色拒绝；LLM 只做最终汇总 | Observation 仍作为 Prompt 文本；Trace 可能保存敏感行；Agent DTO 问题无长度上限 | Observation 结构化、字段白名单与脱敏；明确“数据不是指令”；最终答案 DLP；问题/session/trace 长度限制 | Observation 注入测试、敏感字段泄露告警 | Medium | Medium | P1 |
| TM-08 | SQL 白名单通过字符串包含被绕过 | INTERNAL 权限 | MySQL 数据机密性和可用性 | 仅 SELECT、禁分号/写关键词、行数和超时、表白名单 | 白名单使用 `lowered.contains`；允许 UNION/函数/注释；应用账号权限未知 | 使用 JSqlParser/AST 提取全部表和语句类型；禁 UNION/子查询或使用命名查询目录；独立只读账号和只读事务 | 记录规范化 AST、表集合、扫描行数、慢查询 | Medium | High | P1 |
| TM-09 | 同一计划并发 approve/reject 产生矛盾审计 | 两个 ADMIN 并发 | 审批状态、审计可信度 | APPROVABLE 状态检查；执行状态 CAS；Action 幂等唯一索引 | approve/reject 是 read-then-write，UPDATE 无旧状态条件；无双人原则 | `UPDATE ... WHERE status IN (...)` 并检查行数；行锁/版本号；生产 MEDIUM/HIGH 要求 creator != approver | 同一 event 短时出现多条相反审批告警 | Low-Medium | Medium | P1 |
| TM-10 | 批量调用 RAG/Agent/Rewrite 消耗模型额度 | 可匿名 WS 或任意用户账号 | 预算、延迟、可用性 | Harness 超时/重试、部分输入上限 | 无用户/来源限流、无 Token 预算；重试放大成本 | Gateway + Redis Token Bucket；单用户并发、日预算、Prompt Token 上限；熔断与排队 | QPS、Token/user、重试倍数、拒绝率 | High | Medium | P1 |
| TM-11 | 用户被降权后旧 JWT 仍保留 ADMIN/INTERNAL | 已签发高权限 Token | 内部工具与修复权限 | Token 签名和过期 | Filter 完全信任 Claim，不查询当前角色；24h 默认有效；无撤销列表 | 缩短 Access Token；Refresh Token；`tokenVersion`/角色版本；敏感操作实时查角色；撤销列表 | 已降权用户继续调用敏感接口告警 | Medium | High | P1 |
| TM-12 | Prompt、记忆、日志或 SQL 行被 Trace/Debug 长期保存 | 使用 RAG/Agent | 用户隐私、内部数据 | INTERNAL 才能查看多数 Trace | PromptDebug 对 USER 可回传；Trace/日志缺少统一脱敏和保留期证据 | 生产禁 Debug 字段；字段级脱敏；Trace 分级；TTL/删除策略；日志禁止原始 SQL/Secret | DLP 扫描 Trace 与日志，保留期巡检 | Medium | Medium | P1 |
| TM-13 | 公开 Swagger/Actuator 帮助枚举内部能力 | 可访问服务 | 架构信息、指标 | 只公开部分 Actuator | Swagger、metrics/prometheus 对匿名开放 | 生产关闭 Swagger；Actuator 独立端口/网段和鉴权；仅公开 health liveness | 非监控来源访问 Actuator 告警 | Medium | Low-Medium | P2 |

## Criticality calibration

**P0 / 上线阻断**

- 默认 JWT 密钥、匿名 WebSocket Debug 泄露、跨用户记忆 Debug、匿名任意文件上传。
- 持久化 Prompt Injection 若系统用于真实发布风控，也按 P0 处理，因为它会破坏核心业务结论的完整性。

**P1 / 上线前应完成**

- 解析隔离、SQL AST 白名单、审批原子状态迁移、模型限流、Token 撤销、Trace 脱敏。

**P2 / 硬化项**

- Swagger/Actuator 收口、安全响应头、长期审计和告警优化。

如果项目始终只在本机、单用户、使用演示数据，TM-01 至 TM-04 的可利用性会下降；但这不能作为公开演示或云部署时的控制措施。

## Focus paths for security review

### 1. 文档上传

现有控制值得保留：

- INTERNAL 权限检查：`KnowledgeDocController.java:53-64`。
- 扩展名、声明 MIME 与 Tika 真实 MIME 联合校验：`KnowledgeDocumentUploadServiceImpl.java:81-96`。
- 文件大小、PDF 页数、提取字符数和解析超时：`KnowledgeDocumentUploadServiceImpl.java:88-121`。
- SHA-256 幂等与解析/切片/Embedding 版本共同判重：`KnowledgeDocumentUploadServiceImpl.java:94-106`。
- 文件名去路径、拒绝 `..`：`KnowledgeDocumentUploadServiceImpl.java:247-256`。

下一轮代码审查应重点验证：

1. DOCX ZIP 解压比、嵌套对象数和图片大小是否受限。
2. Parser 超时后底层 Tika/PDF 任务是否真正退出。
3. 原始 Bucket 是否私有，下载是否设置 `Content-Disposition: attachment` 和 `nosniff`。
4. 上传后是否先进入隔离/审核状态，而不是直接触发 Ingest。
5. 是否有 ClamAV/云扫描或至少文件哈希黑名单。

### 2. RAG Prompt Injection

当前 Prompt 将 L3、Profile、L2、最近对话、Evidence 和 Question 拼为一个用户消息：`RagChatServiceImpl.java:201-227`。系统 Prompt 只规定忠实回答，没有明确“证据和记忆是不可信数据，不得执行其中的指令”：`ChatGenerationServiceImpl.java:22-42`。

建议落地的防线：

1. 建立 `PromptContextEnvelope`，每段带 `sourceType/docId/trustLevel`，序列化为 JSON 或明确 XML 标签。
2. System Prompt 明确优先级：系统规则 > 当前用户问题 > 已验证工具结果 > RAG Evidence > Memory；后两者永远是数据。
3. 文档治理加入 `QUARANTINED/REVIEWED/ACTIVE/REVOKED`，检索只返回 ACTIVE。
4. 入库和出库两次检测 Prompt Injection，命中后降权、隔离或人工审核。
5. USER 响应 DTO 删除 `promptDebug`、`memoryAssemblyDebug`、原始 Observation，仅保留可公开引用。
6. 新增攻击评测：忽略指令、索要 System Prompt、伪造管理员、诱导工具调用、跨用户记忆泄露。

### 3. Agent 工具调用

有利设计：

- 工具来自固定注册表，不支持模型任意声明新工具：`AgentToolRegistry.java:13-24`。
- ReAct/Graph Planner 目前是规则生成，SQL/PromQL/日志参数主要由代码构造：`ReactAgentServiceImpl.java:124-176`。
- 工具自身标记 `internalOnly`，执行前再次检查 mode；Controller 对 INTERNAL mode 再检查 JWT 角色：`ToolAccessGuard.java:20-36`、`ReactAgentServiceImpl.java:92-104`。
- Harness 在执行层提供超时、重试、快照和 fallback，不把可靠性完全交给模型。

下一轮应重点检查：

1. 用 AST 替代 SQL 字符串白名单，并使用数据库只读账号。
2. 日志与 SQL Observation 在进入 Prompt/Trace 前统一脱敏。
3. 为 Agent question、sessionId、traceId 增加长度和字符集约束。
4. Harness Debug 与 Fault Injection 在生产 Profile 完全禁用。
5. 如果未来改成 LLM Planner，必须增加“计划输出 Schema 校验 → 工具策略引擎 → 参数验证 → 人工审批”的独立控制层，不能复用当前只检查工具名的逻辑。

### 4. 受控修复链路

有利设计：

- 受控动作仅三项白名单：`ControlledOperationServiceImpl.java:173-181`。
- INTERNAL 创建计划，ADMIN 审批与执行：`ControlledOperationController.java:26-63`。
- HIGH 风险批量 Repair 禁止自动执行：`ControlledOperationServiceImpl.java:109-112`。
- 旧 Repair/Reindex 和真实 Runbook 入口已返回 409，引导进入受控计划：`KnowledgeDocController.java:96-121`、`AiopsRunbookController.java:27-36`。
- Runbook 有执行后验证、失败状态和转人工数据；数据库有 `(event_id, action_type, idempotency_key)` 唯一索引：`ai_observability_pg16_init.sql:139-174`。
- 执行状态使用 `WHERE status='APPROVED'` 原子迁移：`AiopsEventServiceImpl.java:166-175`。

下一轮应重点检查：

1. approve/reject 改成原子条件更新，并给事件增加乐观锁版本。
2. 计划快照应包含不可变参数哈希，审批后禁止修改 Metadata。
3. ADMIN 执行前重新读取角色、计划状态和风险级别，不只依赖长效 JWT Claim。
4. 生产环境启用职责分离，至少 `creatorUserId != approverUserId`。
5. Repair 验证要绑定同一 `traceId/taskId/docId/chunkVersion`，避免验证了另一轮任务。
6. 审计日志写入 append-only 存储或定期签名，防止数据库管理员静默篡改。

### Review outcome

该项目不是“LLM 直接拿生产写权限”的结构。当前 Agent 工具规划是规则式，真实修复还经过白名单、角色、审批、状态机、幂等和验证，方向正确。主要差距集中在身份边界、调试接口、文件入口和不可信上下文隔离。先完成 P0 项，再讨论更复杂的 Agent 防护，会更符合风险优先级。

