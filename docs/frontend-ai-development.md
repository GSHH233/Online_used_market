# AI 前端开发文档

本文档面向 Vue3 前端开发，覆盖当前 AI 发布助手、RAG Chat、ReAct Agent、Graph/DAG Agent、知识库、记忆调试与评测辅助页面。

## 1. 基础约定

### 1.1 Base URL

本地开发默认：

```text
http://localhost:8080
```

WebSocket：

```text
ws://localhost:8080/ws/ai/rag-chat
```

### 1.2 统一响应格式

所有 HTTP 接口默认返回：

```json
{
  "code": 200,
  "message": "成功",
  "data": {}
}
```

前端只在 `code === 200` 时使用 `data`，否则展示 `message`。

### 1.3 鉴权

除登录、注册、短信、WebSocket 外，AI 调试接口默认建议携带 JWT：

```http
Authorization: Bearer <token>
```

登录接口：

```http
POST /api/auth/Login
```

请求：

```json
{
  "account": "testuser",
  "password": "change-me",
  "loginType": "USERNAME_PASSWORD"
}
```

注册接口：

```http
POST /api/auth/register
```

请求：

```json
{
  "username": "testuser",
  "password": "change-me",
  "confirmPassword": "change-me",
  "nickname": "测试用户",
  "phone": "13800138000"
}
```

## 2. 推荐页面结构

建议前端把 AI 能力拆成以下页面：

```text
/ai/publish-assist       AI 发布助手
/ai/rag-chat             RAG 智能对话
/ai/knowledge            知识库管理
/ai/retrieval-debug      检索调试
/ai/memory-debug         L2/L3/Profile 记忆调试
/ai/react-agent          ReAct 工具 Agent
/ai/graph-agent          Graph/DAG Agent
/ai/eval-dashboard       评测报告看板
```

## 3. AI 发布助手

### 3.1 文案优化

```http
POST /api/ai/products/optimize
```

请求：

```json
{
  "title": "二手RTX3060显卡",
  "description": "有跑分截图，温度偏高，无原盒",
  "brand": "NVIDIA",
  "conditionText": "正常使用",
  "categoryName": "显卡",
  "expectedPrice": 1200
}
```

前端展示重点：

```text
优化标题
优化描述
风险提示
引用 sources
traceId
```

### 3.2 多阶段发布助手

```http
POST /api/ai/products/publish-assist
```

请求同上。

页面建议：

```text
左侧：商品表单
右侧：结果面板
底部：pipeline trace
```

结果重点字段：

```text
optimizedTitle
optimizedDescription
riskLevel
riskReasons
pricingSuggestion
canPublish
sources
trace
```

## 4. RAG Chat

### 4.1 HTTP Eval 调试

```http
POST /api/ai/rag-chat/eval
```

请求：

```json
{
  "type": "CHAT_START",
  "requestId": "rag-debug-001",
  "question": "RTX3060 有跑分截图但温度偏高，买家问是不是矿卡，我该怎么回应？",
  "topK": 5,
  "filter": {
    "sourceType": "RULE",
    "category": "显卡",
    "riskLevel": null
  }
}
```

响应 `data` 重点字段：

```text
answer
sources
lowConfidence
confidenceLevel
confidenceDebug
retrievalMode
retrievalDebug
preferenceAutofillDebug
promptDebug
memoryAssemblyDebug
```

注意：

```text
promptDebug 和 memoryAssemblyDebug 只用于 debug/eval 页面。
正式用户页默认隐藏，避免暴露系统提示和内部记忆组装细节。
```

### 4.2 WebSocket 流式对话

连接：

```text
ws://localhost:8080/ws/ai/rag-chat
```

发送开始消息：

```json
{
  "type": "CHAT_START",
  "requestId": "ws-rag-001",
  "question": "二手手机标题写全新未拆封但无发票有风险吗？",
  "topK": 5,
  "filter": {
    "sourceType": "RULE"
  }
}
```

取消消息：

```json
{
  "type": "CHAT_CANCEL",
  "requestId": "ws-rag-001"
}
```

心跳：

```json
{
  "type": "CHAT_PING",
  "requestId": "ping-001"
}
```

服务端统一消息包：

```json
{
  "type": "CHAT_DELTA",
  "requestId": "ws-rag-001",
  "ts": 1780000000000,
  "payload": {}
}
```

前端需要处理的消息类型：

```text
CHAT_ACK      请求已接收
CHAT_DELTA    回答增量片段
CHAT_SOURCE   引用来源
CHAT_DONE     完成消息
CHAT_ERROR    错误消息
CHAT_CANCEL   取消确认
CHAT_PONG     心跳响应
```

## 5. 知识库管理

### 5.1 创建知识文档

```http
POST /api/ai/knowledge/docs
```

请求：

```json
{
  "title": "显卡矿卡风险规则",
  "content": "RTX3060 若温度偏高，仅凭跑分截图无法排除矿卡风险...",
  "sourceType": "RULE",
  "sourceUri": "manual://gpu-risk",
  "tags": "显卡,矿卡,跑分,温度"
}
```

`sourceType` 常见值：

```text
RULE
MARKET
POLICY
FAQ
```

### 5.2 重建索引

```http
POST /api/ai/knowledge/reindex/{docId}
```

页面建议：

```text
展示 docId、任务状态、创建时间、更新时间
点击重建后刷新检索调试页
```

### 5.3 统一检索

```http
POST /api/ai/knowledge/search
```

请求：

```json
{
  "question": "RTX3060 温度偏高是不是矿卡",
  "topK": 5,
  "mode": "HYBRID",
  "filter": {
    "sourceType": "RULE",
    "category": "显卡",
    "riskLevel": null
  }
}
```

`mode`：

```text
VECTOR_ONLY
KEYWORD_ONLY
HYBRID
```

响应重点：

```text
query
topK
total
hits
retrievalMode
debugInfo
vectorHitCount
keywordHitCount
```

### 5.4 Milvus by-doc 调试

```http
GET /api/ai/knowledge/debug/milvus/by-doc/{docId}
```

用于查看某个 doc 的向量 chunk 是否写入成功。

## 6. 记忆系统调试

### 6.1 写入 L2 记忆

```http
POST /api/ai/l2-memory/debug/write
```

Query 参数：

```text
userId=1
sessionId=debug-session
memoryType=PREFERENCE
content=PREFERENCE|writingStyle=详细解释风险原因，偏保守如实
importance=0.3
metadataJson={"schema":"user_preference_v1","key":"writingStyle","value":"详细解释风险原因，偏保守如实"}
```

建议前端做成表单：

```text
userId
sessionId
memoryType
content
importance
metadataJson
```

### 6.2 查询 L2 记忆

```http
GET /api/ai/l2-memory/debug/query
```

Query 参数：

```text
userId=1
sessionId=debug-session
q=偏好品牌预算成色交易风险文案
topK=10
threshold=0.05
```

注意：

```text
q 在 Postman 或浏览器里要 URL encode。
如果直接用 query string，空格可能导致请求解析异常。
```

### 6.3 查询结构化 Profile

```http
GET /api/ai/l2-memory/debug/profile
```

Query 参数：

```text
userId=1
sessionId=debug-session
```

前端展示：

```text
profilePromptBlock
rawPreferences
结构化字段：brand/category/priceRange/condition/transaction/riskTolerance/writingStyle
```

### 6.4 长期 Memory Graph 抽取

```http
POST /api/ai/memory-graph/debug/extract
```

请求：

```json
{
  "userId": "1",
  "sessionId": "debug-session",
  "content": "用户长期学习 Java，用户学习 K8s，用户关注 AI Infra，用户喜欢动漫。",
  "source": "debug"
}
```

响应重点：

```text
nodeCount
edgeCount
nodes
edges
```

前端展示建议：

```text
左侧：原始记忆文本
中间：抽取出的 node 列表
右侧：edge 列表或关系图
```

### 6.5 查询 Memory Graph 节点

```http
GET /api/ai/memory-graph/debug/nodes?userId=1&topic=tech&limit=50
```

Query 参数：

```text
userId 必填
topic 可选
limit 可选，默认 50，最大 500
```

节点字段：

```text
id
nodeType
label
content
topic
importance
confidence
source
metadata
createdTime
updatedTime
```

### 6.6 查询 Memory Graph 边

```http
GET /api/ai/memory-graph/debug/edges?userId=1&fromLabel=Java&limit=50
```

Query 参数：

```text
userId 必填
fromLabel 可选
toLabel 可选
limit 可选，默认 50，最大 500
```

边字段：

```text
id
fromNodeId
toNodeId
fromLabel
toLabel
edgeType
weight
confidence
evidence
source
metadata
createdTime
updatedTime
```

前端关系图建议：

```text
node.label 作为节点名称
edge.edgeType 作为边标签
edge.weight/confidence 用于边粗细或透明度
```

## 7. ReAct 工具 Agent

### 7.1 线性 ReAct-style Agent

```http
POST /api/ai/agent/react-chat
```

请求：

```json
{
  "sessionId": "react-debug-session",
  "traceId": "react-trace-001",
  "question": "RTX3060 有跑分截图但温度偏高，发布有什么风险？",
  "maxSteps": 3,
  "mode": "USER"
}
```

`mode`：

```text
USER      普通用户模式，内部工具拒绝调用
INTERNAL  内部调试模式，可调用 mysql_read/log_query/vector_debug
```

响应重点：

```text
answer
agentTrace
observations
sources
success
debugInfo
```

前端页面建议：

```text
左侧：问题输入、mode、maxSteps
中间：最终回答
右侧：agentTrace 时间线
底部：observations 表格
```

## 8. Graph/DAG Agent

### 8.1 DAG 工具 Agent

```http
POST /api/ai/agent/graph-chat
```

请求：

```json
{
  "sessionId": "graph-debug-session",
  "traceId": "graph-trace-001",
  "question": "帮我查一下索引任务数据库状态、向量召回命中和 trace 报错日志",
  "maxSteps": 5,
  "mode": "INTERNAL"
}
```

响应重点：

```text
graph
agentTrace
observations
sources
answer
elapsedMs
debugInfo
```

`graph` 节点字段：

```text
nodeId
type        TOOL / MERGE / FINAL
tool
dependsOn
input
status      PENDING / RUNNING / SUCCESS / FAILED / DENIED / SKIPPED
summary
elapsedMs
```

前端渲染建议：

```text
1. DAG 视图：节点卡片 + 依赖边
2. 节点状态颜色：
   SUCCESS 绿色
   FAILED 红色
   DENIED 橙色
   RUNNING 蓝色
   PENDING 灰色
3. 点击节点展示 input、summary、elapsedMs
4. 工具 Observation 用表格展示 rows/columns
5. trace 用时间线展示 phase/tool/status/elapsedMs
```

当前 DAG 逻辑：

```text
工具节点入度为 0，可并行执行
observation_merge 依赖所有工具节点
final_answer 依赖 observation_merge
```

## 9. 工具调试接口

### 9.1 MySQL 只读查询

```http
POST /api/ai/tools/mysql/read
```

请求：

```json
{
  "sql": "select id, title, status, created_time from ai_knowledge_doc order by id desc",
  "limit": 20
}
```

注意：

```text
仅允许 SELECT。
后端有表白名单和危险 SQL 拦截。
前端只在 INTERNAL 调试页面开放。
```

### 9.2 向量/检索调试

```http
POST /api/ai/tools/mysql/milvus/read
```

请求：

```json
{
  "question": "RTX3060 温度偏高是不是矿卡",
  "topK": 5,
  "filter": {
    "sourceType": "RULE"
  }
}
```

响应为通用 `ToolQueryResultVO`：

```text
tool
target
limit
total
truncated
elapsedMs
columns
rows
summary
```

## 10. 评测看板

当前评测脚本输出 Markdown 报告，前端可以先做“报告文件展示”或“本地上传解析”。

重要报告：

```text
retrieval_eval_report_*.md
rag_chat_eval_report_*.md
react_agent_eval_report_*.md
agent_eval_report_*.md
memory_priority_eval_report_*.md
```

建议展示指标：

```text
Retrieval:
Recall@K
MRR
引用命中率

RAG Chat:
通过率
拒答准确率
证据外编造次数
P95 latency

ReAct / Graph Agent:
工具选择准确率
trace 完整率
observation 使用率
最终回答引用覆盖率
节点成功率
DAG elapsedMs

Memory:
M1 Store: Precision / Recall / F1 / Clarity
M2 Recall: Recall@K / MRR
M3 Consolidation: DuplicateRate / MemoryLengthCurve / EntropyDrift / RecallQualityCurve
M4 Context Assembly: ContextPrecision / NoiseRate / PromptKeywordCoverage
M5 Graph Expansion: EdgePrecision / EdgeRecall / EdgeF1 / ForbiddenEdgeRate
```

## 11. 前端状态管理建议

建议建立这些 store：

```text
authStore:
token
currentUser

aiChatStore:
messages
sources
currentTraceId
lowConfidence
debugPanelVisible

knowledgeStore:
docs
searchHits
reindexTasks

memoryStore:
l2Hits
profile
memoryAssemblyDebug

agentStore:
agentTrace
observations
graph
selectedNode
```

## 12. 安全与展示注意事项

```text
1. promptDebug 默认只在 INTERNAL/debug 页面展示。
2. mysql_read/log_query 只放在内部调试页面。
3. USER 模式下遇到 DENIED observation，要展示“无权限调用内部工具”。
4. sources 是 RAG 可解释性核心，正式回答页必须展示。
5. memoryAssemblyDebug 可以帮助调试偏好污染，但正式用户页不展示。
6. Graph/DAG Agent 目前是最小调度层，适合作为高级调试/演示页，不建议替代普通 RAG Chat 首页。
```

## 13. 优先开发顺序

```text
1. 登录和 token 注入
2. RAG Chat 页面，优先 WebSocket 流式体验
3. 知识库创建、reindex、search 调试页
4. 记忆调试页：L2 query + Profile
5. ReAct Agent 调试页
6. Graph/DAG Agent 可视化页
7. Eval Dashboard
```
