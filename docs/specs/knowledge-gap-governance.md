# 知识缺口治理闭环 Spec

状态：`DONE`  
更新时间：2026-08-01  
验收证据：`eval/reports/knowledge_gap_eval_report.md`，26/26 通过，治理链路通过率 100%，P95=5348.5ms。  
范围：拒答案例沉淀、知识更新、自动回归  
现有基础：RAG Chat lowConfidence、拒答规则、知识文档治理、RabbitMQ Ingestion、Repair/Consistency、Eval 表

## 1. 背景与目标

当前系统能在证据不足时拒答，但拒答只存在于响应和评测报告中，无法转化为待补充知识。本 Spec 将真实拒答沉淀为知识缺口，并在补文档后自动验证问题是否解决。

## 2. 状态机

```text
OPEN -> TRIAGED -> DOC_LINKED -> INDEXING -> REGRESSING -> RESOLVED
                                                -> REGRESSION_FAILED
     -> DISMISSED
```

## 3. 功能需求

### GAP-FR-001 自动沉淀

RAG Chat 满足任一条件时创建或合并缺口：

- `lowConfidence=true`；
- sources 为空且输出包含标准拒答原因；
- 检索双路均为空；
- 用户对回答提交“未解决”。

记录 query、normalized query、reason code、traceId、sessionId、检索模式、topK、sources 和 debug 摘要。

### GAP-FR-002 去重与聚合

使用标准化 query 指纹和 reason code 合并相同缺口，维护 `occurrenceCount`、`firstSeenAt`、`lastSeenAt`，并保留最近 traceId 样本。不得只按原始字符串完全匹配。

### GAP-FR-003 人工治理

INTERNAL 可分类和关联已有 doc；ADMIN 可创建/更新知识文档、触发入库、关闭或驳回缺口。

### GAP-FR-004 自动回归

关联 doc 入库成功且三存储一致后，系统对缺口原 query 执行定向回归：

1. Hybrid 检索命中关联 doc。
2. RAG Chat 不再因相同 reason code 拒答。
3. Source 引用包含关联 doc。
4. 结果写入 `ai_eval_run` 和 `ai_eval_case_result`。

全部通过才可标记 `RESOLVED`；失败进入 `REGRESSION_FAILED`，保留报告并允许修订知识后重跑。

### GAP-FR-005 指标

```text
gap_resolution_rate = RESOLVED / 已完成治理的缺口
recurrence_rate = 已解决后再次出现相同指纹的缺口 / 已解决缺口
average_gap_age = 当前时间 - first_seen_at（仅未解决）
refusal_rate = 拒答会话数 / RAG Chat 会话数
```

## 4. 数据模型

新增 `ai_knowledge_gap`：

| 字段 | 说明 |
| --- | --- |
| gap_id、fingerprint | 主键与去重键 |
| query、normalized_query | 原始和标准化问题 |
| reason_code | NO_RETRIEVAL / LOW_CONFIDENCE / MISSING_FIELDS / USER_UNRESOLVED |
| status、priority | 状态与优先级 |
| occurrence_count | 出现次数 |
| linked_doc_id、linked_task_id | 知识与入库任务 |
| latest_trace_id | 最近链路 |
| first_seen_at、last_seen_at、resolved_at | 生命周期 |
| metadata_json | 检索和证据摘要 |

新增 `ai_knowledge_gap_regression`：记录每次回归的 retrievalHit、sourceHit、rejectResolved、latency、evalRunId 和错误信息。

## 5. API 契约

```http
GET   /api/ai/knowledge/gaps?status=&priority=&limit=
GET   /api/ai/knowledge/gaps/{gapId}
PATCH /api/ai/knowledge/gaps/{gapId}
POST  /api/ai/knowledge/gaps/{gapId}/link-doc
POST  /api/ai/knowledge/gaps/{gapId}/regress
GET   /api/ai/knowledge/gaps/{gapId}/regressions
GET   /api/ai/knowledge/gaps/metrics?from=&to=
```

`link-doc` 只建立关系；`regress` 必须确认入库任务成功且三存储一致，不能绕过索引状态。

## 6. 安全与质量规则

- USER 不可访问知识缺口管理接口。
- 保存 query 前移除 token、手机号、邮箱等敏感信息。
- 低置信度不等于一定缺知识，`MISSING_FIELDS` 可以聚合但默认不触发补文档。
- 自动回归使用原问题和固定断言，不允许为通过测试改写成更容易的问题。
- 知识文档更新必须保留版本、来源可信度和有效期。

## 7. 验收标准

### GAP-AC-001 拒答沉淀

Given RAG 双路检索为空，When 返回标准拒答，Then 创建 OPEN 缺口并保存 traceId 和 reasonCode。

### GAP-AC-002 重复聚合

Given 同一语义问题重复出现，When 再次拒答，Then不新增重复缺口，occurrenceCount 增加。

### GAP-AC-003 区分缺知识与缺字段

Given 用户只问“这个能卖吗”，When 系统要求补充商品信息，Then reasonCode 为 MISSING_FIELDS，不能自动建议新增规则文档。

### GAP-AC-004 知识更新回归成功

Given 缺口关联的新文档已完成三存储入库，When 执行回归，Then检索和引用均命中该 doc，缺口变为 RESOLVED。

### GAP-AC-005 回归失败不关闭

Given 新文档未被召回，When 执行回归，Then状态为 REGRESSION_FAILED，并保留失败断言和 evalRunId。

## 8. 评测映射

新增 `knowledge_gap_eval_dataset.json` 与 `knowledge_gap_eval.py`，至少 25 条，报告输出 Capture Precision、Dedup Accuracy、Regression Closure Rate、Recurrence Rate 和 P95。
