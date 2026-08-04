# 企业级 RAG 入库切片与 Query Rewrite Spec

状态：`IMPLEMENTED_PENDING_EVAL`

更新时间：2026-07-24

关联能力：知识文档上传、RAG Ingestion Job、Hybrid + RRF 检索、RAG Chat、Agent `knowledge_search` 工具、DB/ES/Milvus 一致性治理。

## 1. 背景

当前知识入库以 JSON 纯文本为主，`ChunkingServiceImpl` 先调用 `TokenTextSplitter`，随后按 600 字符、100 字符重叠进行二次滑窗。现有实现可以支撑小规模规则文档，但存在以下问题：

- 不支持 Markdown、PDF、Word 文件直接上传和解析。
- 二次字符切分破坏了 Token 切分语义，`tokenCount` 实际记录字符数。
- 不感知标题、段落、列表、页码和表格，可能拆开完整规则。
- 重建索引前先删除旧版本，中途失败时存在文档暂时不可检索的风险。
- 检索只对原始问题做 `trim`，无法处理多轮省略、复杂多意图和用户术语与知识库术语不一致的问题。

本 Spec 将知识链路升级为：

```text
多格式解析
-> 结构化章节拆分
-> LLM 语义边界识别
-> 过长片段拆分
-> 过短片段合并
-> 质量校验与版本化入库
-> 原始 Query + 结构化多路动态改写
-> 加权 RRF 融合
```

## 2. 目标与非目标

### 2.1 目标

1. 支持 Markdown、可提取文本的 PDF、DOC/DOCX 文档上传和解析。
2. 建立结构优先、LLM 增强、Token 兜底的可追踪切片流程。
3. 保证切片不丢原文、不无故重复、可按来源位置回溯。
4. 使用真实 Token 计数，并将切片参数配置化和版本化。
5. 为知识检索提供主改写、子查询和同义改写三类动态分支。
6. 原始 Query 永远参与召回，改写失败时可无损降级。
7. 使用离线评测证明切片和改写提升检索效果，而不是通过修改测试问题规避失败。

### 2.2 非目标

- 第一阶段不支持扫描 PDF OCR；扫描件进入 `UNSUPPORTED_OCR_REQUIRED`，不得以空文本入库。
- 第一阶段不保证复杂 Word 合并单元格、公式和图表的完整语义；普通表格可提取，复杂表格记录质量警告。
- Query Rewrite 只服务知识检索，不改写 SQL、PromQL、日志表达式、错误码和其他工具参数。
- 不让 LLM 直接生成、删除或改写知识原文。
- 不在本期迁移现有向量库或改变 DB/ES/Milvus 的职责。

## 3. 总体架构

```text
Multipart 上传 / 现有纯文本 API
  -> 文件安全校验与 SHA-256
  -> 原文件保存 MinIO
  -> DocumentParserRouter
       -> Markdown Parser
       -> PDF Parser
       -> Tika Word Parser
  -> ParsedDocument / ParsedSection
  -> EnterpriseChunkingPipeline
       -> StructuralSectionSplitter
       -> SemanticBoundarySplitter
       -> OversizeChunkSplitter
       -> ShortChunkMerger
       -> ChunkQualityValidator
  -> Staging Document Version
  -> DB Chunk -> Embedding -> Milvus -> ES
  -> 三存储一致性验证
  -> activeVersion 原子切换
```

检索链路：

```text
原始 Query
  -> RewriteTrigger
       -> 快速路径：只使用原始 Query
       -> 改写路径：一次 LLM 调用生成 RewritePlan
  -> Original / Main / Sub / Synonym Queries
  -> Vector + BM25 分支召回
  -> chunkId 去重 + metadata 过滤
  -> Weighted RRF
  -> 最终 TopK
```

## 4. 多格式解析

### INGEST-FR-001 解析器抽象

新增统一接口，不允许 Controller 或索引服务直接依赖某种文件解析器：

```java
interface KnowledgeDocumentParser {
    boolean supports(String mimeType, String fileName);
    ParsedDocument parse(Resource resource, ParseContext context);
}
```

解析使用成熟框架，业务层保留统一中间模型和治理逻辑：

- Markdown：Spring AI `MarkdownDocumentReader`，保留标题层级、段落、列表和代码块类型。
- PDF：Spring AI `PagePdfDocumentReader`，保留页码；有目录的 PDF 可尝试段落模式，失败后回退页面模式。
- DOC/DOCX：Spring AI `TikaDocumentReader`；复杂表格后续可增加 Apache POI 专用解析器。

### INGEST-FR-002 统一中间模型

```json
{
  "fileName": "phone-rule-v2.pdf",
  "mimeType": "application/pdf",
  "fileHash": "sha256...",
  "parserType": "SPRING_AI_PDF",
  "parserVersion": "1.0.3-v1",
  "title": "二手手机发布规则",
  "sections": [
    {
      "sectionId": "s-001",
      "headingPath": ["手机类目", "发票要求"],
      "pageFrom": 3,
      "pageTo": 3,
      "contentType": "PARAGRAPH",
      "content": "...",
      "sourceStart": 1200,
      "sourceEnd": 1640
    }
  ],
  "warnings": []
}
```

`sourceStart/sourceEnd` 使用规范化原文坐标；如果解析器无法提供源文件字节坐标，至少提供规范化文本坐标和页码。

### INGEST-FR-003 文件安全

- 允许扩展名：`.md`、`.markdown`、`.pdf`、`.doc`、`.docx`。
- 同时校验扩展名、Content-Type 和解析器检测的真实 MIME。
- 文件大小、PDF 页数、提取文本长度和解析时间均配置上限。
- 拒绝加密文件、宏文件、可执行文件、Zip Bomb 和无法识别格式。
- 不直接解析用户提供的远程 URL，避免 SSRF。
- 解析失败保存任务错误和文件摘要，不把空内容写入正式知识库。

## 5. 切片流水线

### CHUNK-FR-001 结构化章节拆分

优先使用文档结构划分语义范围：

- Markdown 标题 `#` 至 `######`。
- PDF 页码、目录和段落。
- Word Heading 样式、段落、列表和表格。
- 规则编号，如“第一条”“1.1”“（一）”。

标题不单独形成无上下文短 Chunk。标题路径写入 metadata，并可作为 Chunk 内容前缀参与 Embedding，但不得重复写入原文覆盖率统计。

### CHUNK-FR-002 LLM 语义切分

LLM 只返回段落边界和主题标签，不返回改写后的正文：

```json
{
  "groups": [
    {
      "startParagraphId": "p-001",
      "endParagraphId": "p-004",
      "topic": "无发票商品的发布条件",
      "reason": "条件和处理建议属于同一规则"
    }
  ]
}
```

约束：

- 温度为 0，使用结构化输出和固定 Prompt 版本。
- 单次输入超过语义切分预算时，先按段落预切为分析窗口。
- LLM 不得改变段落顺序，不得遗漏、复制或交叉引用不存在的段落 ID。
- 结果按 `fileHash + parserVersion + splitterVersion + modelId + promptVersion` 缓存。
- 超时、限流、结构化解析失败或覆盖率校验失败时，降级为结构化规则切分。
- Trace 记录是否调用 LLM、模型、耗时、缓存命中、降级原因和边界数量。

### CHUNK-FR-003 过长片段拆分

使用与 Embedding 模型匹配的 Tokenizer 统计真实 Token：

- `targetTokens`：默认 450。
- `maxTokens`：默认 600。
- `overlapTokens`：默认 80。
- 优先在段落、句号、换行和列表项边界切分。
- 不可拆分的超长表格行或代码块单独标记 `OVERSIZE_ATOMIC_BLOCK`，不得静默截断。

### CHUNK-FR-004 过短片段合并

低于 `minTokens`（默认 100）的片段只有满足以下条件才合并：

1. 来源于同一文档版本。
2. `headingPath` 相同或存在直接父子关系。
3. `contentType` 兼容。
4. 来源位置相邻，或相邻候选中语义相似度最高。
5. 合并后不超过 `maxTokens`。
6. 不跨页合并表格、警告、禁止条款和独立规则编号。

没有安全合并对象时允许保留短 Chunk，不以降低短片段率为理由破坏规则完整性。

### CHUNK-FR-005 质量校验

每次入库必须计算：

- 原文段落覆盖率。
- 重复段落率。
- 空 Chunk 数。
- Token 长度 P50/P95/Max。
- 过短和过长 Chunk 比例。
- 标题路径、页码和来源坐标覆盖率。
- LLM 边界有效率和降级率。

硬性不变量：有效段落必须且只能被一个非 overlap 主体范围覆盖；用于滑窗的 overlap 单独标记，不计为异常重复。

## 6. 版本化与原子入库

### INGEST-FR-004 版本键

幂等键至少包含：

```text
fileHash + parserVersion + chunkStrategyVersion + embeddingModel
```

相同版本重复提交不得重复生成正式 Chunk。解析器、Prompt、切片参数或 Embedding 模型变化时生成新版本。

### INGEST-FR-005 原子切换

禁止继续采用“先删除旧索引、再建立新索引”的正式流程。新流程为：

```text
创建 STAGING 版本
-> 完成 DB/ES/Milvus 写入
-> 三存储 chunkId 和数量校验
-> 将新版本切换为 ACTIVE
-> 旧版本改为 RETIRED
-> 延迟清理旧版本
```

任一步失败时保留旧 ACTIVE 版本，任务进入 `FAILED` 或 `ESCALATED`，支持重试和人工 Repair。

### INGEST-FR-006 异步任务

RabbitMQ 消息只传 `taskId/docId/documentVersion`。数据库任务表是权威状态源，消费者必须支持幂等、有限重试、死信记录和失败原因查询。

## 7. 结构化多路动态 Query Rewrite

### REWRITE-FR-001 使用范围

统一 Query Rewrite 仅接入：

- `POST /api/ai/knowledge/search`。
- RAG Chat 检索阶段。
- ReAct/DAG 中的 `knowledge_search` 工具。

以下内容禁止进入通用改写：SQL、PromQL、日志表达式、错误码和其他工具参数。

### REWRITE-FR-002 动态触发

保留快速路径，不对每个问题强制调用 LLM：

- 简短、实体明确且首次召回置信度充分：只使用原始 Query。
- 明显多轮指代、复杂多意图：检索前生成 RewritePlan。
- 首次召回为空或低置信度：生成 RewritePlan 后执行第二次召回。
- 改写总耗时超过预算：立即降级原始 Query。

触发决策及原因必须写入 debugInfo/Trace。

### REWRITE-FR-003 单次结构化生成

一次 LLM 调用同时生成三类分支：

```json
{
  "originalQuery": "这个没票还写全新能发吗",
  "mainQuery": "无发票的二手手机以全新商品发布是否存在风险",
  "subQueries": [
    "无发票手机的发布要求是什么",
    "二手商品宣称全新存在哪些风险"
  ],
  "synonymQueries": [
    "没有购买凭证的未拆封手机能否发布"
  ],
  "mustKeepTerms": ["无发票", "全新"],
  "entities": [
    {"type": "CATEGORY", "value": "手机"}
  ],
  "negations": [],
  "rewriteReason": "口语规范化并拆分两个风险意图"
}
```

分支上限：

- 原始 Query：固定 1 条。
- 主改写：最多 1 条，用于补全语义和规范表达。
- 子查询：最多 3 条，只在存在多个可独立检索意图时生成。
- 同义改写：最多 2 条，用于术语和表达差异，不得新增事实条件。

### REWRITE-FR-004 忠实度保护

以下内容必须原样保留或以结构化约束传递：

- 商品型号、品牌和专有名词。
- 金额、日期、比例、数量和版本号。
- 错误码、规则编号和引用编号。
- 否定词、比较关系和条件范围。
- 用户已提供的 metadata filter。

若主改写或任一分支违反 `mustKeepTerms/entities/negations`，丢弃该分支；全部分支不合格时只使用原始 Query。

### REWRITE-FR-005 召回与融合

默认分支权重：

| 分支 | 权重 |
| --- | ---: |
| Original | 1.00 |
| Main | 1.00 |
| Sub Query | 0.85 |
| Synonym | 0.65 |

默认通道：

- Original：Vector + BM25。
- Main：Vector + BM25。
- Sub Query：Vector，必要时开启 BM25。
- Synonym：Vector。

加权 RRF：

```text
score(chunk) += branchWeight * channelWeight / (rrfK + rank)
```

所有分支结果按 `chunkId` 去重，再应用知识治理和 metadata 过滤。分支数量不得线性扩大最终 TopK；系统使用总候选预算限制整体召回数量。

### REWRITE-FR-006 降级与缓存

- Rewrite 超时、模型异常、JSON 解析失败、实体丢失时使用原始 Query。
- 缓存键包含规范化 Query、必要会话摘要、Rewrite Prompt 版本和模型 ID。
- 缓存不得包含用户敏感信息；会话相关改写设置短 TTL。
- 响应记录 `rewriteUsed/rewriteMode/rewriteQueries/fallbackReason/rewriteElapsedMs`。

## 8. 配置建议

```yaml
ai:
  knowledge:
    ingestion:
      max-file-size-mb: 20
      max-extracted-chars: 2000000
      parser-timeout-ms: 30000
    chunking:
      strategy-version: structure-semantic-v1
      target-tokens: 450
      max-tokens: 600
      min-tokens: 100
      overlap-tokens: 80
      semantic-enabled: true
      semantic-model-id: configured-chat-model
      semantic-timeout-ms: 8000
      semantic-cache-ttl-minutes: 1440
      semantic-cache-max-entries: 2000
    query-rewrite:
      enabled: true
      shadow-mode: true
      model-id: configured-chat-model
      cache-ttl-seconds: 300
      cache-max-entries: 1000
      timeout-ms: 2500
      max-sub-queries: 3
      max-synonym-queries: 2
      low-confidence-threshold: 0.35
      total-candidate-budget: 60
```

所有参数必须支持环境变量覆盖。首轮上线使用 `shadow-mode=true`，记录改写结果但仍返回原链路结果，用于离线比较。

## 9. 可观测性

### 入库指标

- `rag_ingest_parse_total{format,status}`
- `rag_ingest_parse_duration_seconds{format}`
- `rag_chunk_count{strategy}`
- `rag_chunk_tokens{statistic="document_max"}`
- `rag_chunk_semantic_fallback_total{reason}`
- `rag_ingest_consistency_total{status}`
- `rag_ingest_active_version_switch_total{status}`

### Rewrite 指标

- `rag_query_rewrite_total{trigger,status}`
- `rag_query_rewrite_duration_seconds`
- `rag_query_rewrite_branch_count{type}`
- `rag_query_rewrite_fallback_total{reason}`
- `rag_query_rewrite_entity_violation_total`
- `rag_retrieval_no_hit_recovery_total`

Trace 中保存摘要和Hash，不保存完整敏感文档或模型隐式思维过程。

## 10. 评测方案

### 10.1 切片评测

新增：

- `eval/datasets/ingestion_chunk_eval_dataset.json`
- `tools/ingestion_chunk_eval.py`

数据至少覆盖：Markdown 5份、普通PDF 5份、多页PDF 3份、普通DOCX 5份、表格DOCX 2份、异常文件2份、重复文件2份、版本更新2份。

指标：

- 有效文件解析成功率。
- 原文段落覆盖率和异常重复率。
- 标题路径/页码覆盖率。
- 空Chunk率、过长率、过短率。
- 切片边界人工准确率。
- 幂等重复数、失败回滚成功率和三存储一致率。

### 10.2 Rewrite评测

新增：

- `eval/datasets/query_rewrite_eval_dataset.json`
- `tools/query_rewrite_eval.py`

在现有61条Retrieval hard case基础上增加：多轮省略、口语表达、同义术语、多意图、型号数字、否定条件、错误改写诱导和无需改写的简单问题。

对同一原始问题进行A/B测试：

- Recall@5、MRR、nDCG@5。
- 无召回恢复率。
- 关键实体保留率。
- 否定条件保留率。
- Rewrite Drift Rate。
- 分支有效率和平均分支数。
- Rewrite P50/P95延迟、Token成本和缓存命中率。

不得修改原始测试问题或Gold文档来迎合改写结果。

## 11. 验收标准

### 切片

- `CHUNK-AC-001`：有效文本型MD/PDF/DOCX解析后正文非空，格式路由正确。
- `CHUNK-AC-002`：Gold段落主体覆盖率100%，非overlap异常重复率为0。
- `CHUNK-AC-003`：普通正文Chunk不超过`maxTokens`，`tokenCount`为真实Token数。
- `CHUNK-AC-004`：LLM不可用时自动降级，重复执行产生确定性一致结果。
- `CHUNK-AC-005`：入库失败时旧ACTIVE版本仍可检索。
- `CHUNK-AC-006`：成功切换后DB/ES/Milvus的文档版本和chunkId完全一致。

### Query Rewrite

- `REWRITE-AC-001`：简单明确问题命中快速路径，不产生不必要LLM调用。
- `REWRITE-AC-002`：复杂多意图问题生成不超过3条子查询，并覆盖Gold意图。
- `REWRITE-AC-003`：关键实体和否定条件保留率100%。
- `REWRITE-AC-004`：Rewrite失败时原始Query检索可继续返回。
- `REWRITE-AC-005`：A/B评测Recall@5不得低于基线，MRR下降不得超过0.01。
- `REWRITE-AC-006`：低置信度/无召回子集的Gold命中数高于基线，并报告实际提升值。
- `REWRITE-AC-007`：SQL、PromQL、日志和其他Agent工具参数不经过该Rewrite链路。
- `REWRITE-AC-008`：Rewrite路径新增P95延迟不超过配置预算；超时自动降级。

## 12. 实施顺序

1. 引入Spring AI Markdown/PDF/Tika Reader，建立解析器接口和统一中间模型。
2. 新增Multipart文档上传、MinIO原文件存储和文件安全校验。
3. 实现结构化切分、真实Token统计、过长拆分和过短合并。
4. 接入LLM语义边界识别、缓存、覆盖校验和规则降级。
5. 增加文档版本字段和STAGING到ACTIVE原子切换。
6. 实现QueryRewritePlan、动态触发、忠实度校验和加权RRF。
7. 先以Shadow Mode运行，对比原链路与新链路。
8. 补齐切片与Rewrite评测；达到验收阈值后灰度启用。

## 13. 风险与决策

| 风险 | 处理 |
| --- | --- |
| LLM切片不稳定 | 只输出段落边界、温度0、缓存、覆盖校验、规则降级 |
| Query改写语义漂移 | 保留Original、mustKeep校验、低权重同义分支 |
| 分支导致延迟和成本上升 | 动态触发、单次结构化调用、总候选预算、缓存 |
| PDF/Word结构丢失 | 页码/标题metadata、质量警告、复杂表格单独迭代 |
| 新版入库失败导致知识不可用 | STAGING构建完成后原子切换，保留旧ACTIVE版本 |
| 测试为通过而修改问题 | 固定原始问题和Gold，通过A/B报告评估实现 |

本Spec完成实现并生成在线评测报告前，不得在开发清单或简历中标记为已完成。
