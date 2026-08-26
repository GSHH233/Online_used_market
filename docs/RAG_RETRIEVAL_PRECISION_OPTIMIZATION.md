# RAG 检索精度与知识生命周期优化

更新时间：2026-08-09

## 目标

本轮优化解决两个问题：

1. RAGAS 显示逻辑知识 Recall 很高，但 TopK 中噪声较多。
2. 存量知识文档全部处于 DRAFT，检索层却只排除了 DISABLED，缺少真正的发布生命周期。

目标门槛：Recall@5 不低于 95%，MRR 不低于 0.90，RAGAS ID Context Precision 不低于 55%，三存储一致率 100%。

## 检索链路

```text
用户问题
  -> Query Rewrite（按需）
  -> Milvus 向量召回 + ES BM25 召回
  -> 生命周期批量过滤（排除未发布/失效版本）
  -> RRF 候选融合（保留 candidateK）
  -> Deterministic Rerank
  -> MMR 去冗余 + Dynamic TopK
  -> RAG Chat
```

Rerank 默认不调用大模型，综合以下信号：

- RRF 原始排名分；
- 问题与 chunk 内容的中英文词项覆盖；
- 问题与标题的匹配度；
- 来源可信等级；
- 候选之间的相似度和逻辑知识 ID，避免重复证据占满 TopK。

第一轮在线评测发现 RRF 分数集中在较窄区间，排名先验权重过高会掩盖内容差异。因此第二轮参数将 RRF / 内容 / 标题 / 来源权重调整为 `0.30 / 0.40 / 0.25 / 0.05`，动态相对阈值调整为 `0.78`，最少结果数调整为 `1`。中文词项同时过滤通用问法，并增加价格、成色、维修、凭证、真伪、站外交易、账号、医药和使用历史等业务概念归一化。

响应中的 `score` 是最终 Rerank 分；`retrievalScore` 保留原始 RRF 分；`rerankReason` 给出各评分分量。RAG Chat 的拒答置信度继续使用原始检索分和词面覆盖，避免因分数尺度变化误判为高置信度。

生命周期过滤按一次检索请求共享文档缓存，并通过 `selectBatchIds` 批量读取 MySQL，避免向量、关键词和多路改写分支产生逐候选查询。Micrometer 指标包括：

- `rag.retrieval.rerank`：二阶段重排耗时 Timer，可观察 P95；
- `rag.retrieval.rerank.candidates`：进入重排的候选数；
- `rag.retrieval.selected.topk`：动态 TopK 最终保留数；
- `rag_retrieval_legacy_draft_total`：兼容路径召回旧 DRAFT 的次数。

## 文档生命周期

新文档执行以下状态机：

```text
DRAFT
  -> 切片与向量化
  -> DB / ES / Milvus staging 写入
  -> 三存储存在性验证
  -> ACTIVE
  -> 被新 revision 替代后 RETIRED（不可检索、保留期内可回滚）
  -> 保留期结束后定时清理三存储 chunk
```

关键字段：

- `logicalKnowledgeId`：同一条业务知识跨版本的稳定 ID；
- `contentHash`：规范化正文 SHA-256，用于幂等去重；
- `revision`：逻辑知识修订号；
- `supersedesDocId`：前序文档 ID；
- `lifecycleManaged`：是否受新生命周期管理；
- `publishedAt`：成功发布时刻。

受管 DRAFT 不参与检索。索引任务成功后自动发布为 ACTIVE；同一 `logicalKnowledgeId` 只允许一个 ACTIVE 修订，旧修订转为 `RETIRED` 并被检索层过滤，但在默认 7 天保留期内保留 DB/ES/Milvus chunk。保留期结束后由定时任务物理清理；回滚必须通过 INTERNAL 创建、ADMIN 审批执行的受控操作，重新构建三存储并验证一致性后才切回 ACTIVE。

## 存量迁移

当前存量文档是在生命周期能力上线前创建的，因此采用渐进迁移：

1. 首次启动保持 `KNOWLEDGE_ALLOW_LEGACY_DRAFTS=true`，旧文档仍可检索。
2. 通过受控 Repair/Reindex 对旧文档逐批重建索引；成功后自动成为受管 ACTIVE。
3. 运行生命周期评测，确认没有多 ACTIVE 修订、重复内容和 ACTIVE 缺索引。
4. 所有文档迁移后设置 `KNOWLEDGE_ALLOW_LEGACY_DRAFTS=false`，关闭兼容路径。

不建议直接执行 `UPDATE ... SET status='ACTIVE'`，因为这样绕过三存储验证。

## 验收命令

```powershell
$env:INTERNAL_BEARER_TOKEN = '<INTERNAL_TOKEN>'
python tools/knowledge_lifecycle_eval.py --base-url http://localhost:8080
python tools/knowledge_lifecycle_eval.py --base-url http://localhost:8080 --strict-no-legacy
```

RAGAS 使用独立 64 位环境：

```powershell
$env:USER_TOKEN = '<USER_TOKEN>'
.\.venv-ragas64\Scripts\python.exe tools\ragas_rag_eval.py `
  --base-url http://localhost:8080 `
  --bearer-token $env:USER_TOKEN
```

默认 RAGAS 门槛：`id_context_recall >= 0.90`、`id_context_precision >= 0.55`、技术错误数为 0。

旧在线版本在新多 Gold 口径下的基线保存在 `eval/reports/ragas_rag_before_rerank_report.md`：ID Context Recall `0.9500`、Precision `0.3517`、P95 `8015.8ms`。当前 50 条 Regression 报告达到 ID Context Recall `1.0000`、Precision `0.6623`、端到端 P95 `3600.8ms`、Retrieval P95 `310ms`，发布门禁通过。首次 15 条封存 Holdout 为 Recall `0.7333`、Precision `0.5667`、10/15 完整召回，结论仍为 `RISK`；该结果用于生成下一轮开发集，不直接针对保留集调参。

## 回滚开关

```text
KNOWLEDGE_RERANK_ENABLED=false
KNOWLEDGE_DYNAMIC_TOPK_ENABLED=false
KNOWLEDGE_ALLOW_LEGACY_DRAFTS=true
```

这些开关允许分别回退二阶段排序、动态 TopK 和生命周期严格模式，不需要回滚数据结构。
