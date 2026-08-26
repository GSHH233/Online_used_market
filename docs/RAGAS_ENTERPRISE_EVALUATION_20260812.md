# RAGAS 企业级评测记录（2026-08-12）

## 1. 本轮目标与约束

Holdout v4 首次盲测结果保持封存。本轮没有读取其逐题失败输出，也没有重跑 Holdout v4，只使用已记录的四类聚合薄弱项构造可见 Development v5：

1. 成色披露；
2. 中等难度改写；
3. 多跳证据；
4. paraphrase。

Development v5 共 32 条，每类 8 条，全部包含完整 `reference` 与原子 `referenceFacts`。数据校验覆盖当前 266 条 RAGAS 用例，字符 bigram 泄漏检查无异常。

## 2. 基线与冻结结果

| 数据集 | Verdict | ID Precision | ID Recall | 完整召回 | Retrieval P95 | E2E P95 | 平均 Prompt Token | Fallback |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Development v5 基线 32 | RISK | 0.5734 | 0.9062 | 25/32 | 336ms | 1748.6ms | 602.2 | 0% |
| Development v5 冻结 32 | PASS | 0.6599 | 1.0000 | 32/32 | 727ms | 2139.0ms | 593.2 | 0% |
| Regression 冻结 50 | PASS | 0.7173 | 1.0000 | 50/50 | 308ms | 1484.5ms | 557.9 | 0% |

Development v5 的完整召回率从 `78.13%` 提升到 `100%`，ID Recall 从 `0.9062` 提升到 `1.0000`，且没有破坏 50 条历史回归。冻结样本的 E2E P95 比基线增加约 `390.4ms`，仍低于 `4s` 门槛；因此只能表述为“召回覆盖显著改善且时延门禁通过”，不能表述为准确率和时延同时大幅提升。

## 3. 架构级修改

1. 将成色意图拆为通用成色、物理损伤及相机、显示器、键盘、耳机、手表等产品专属证据，避免定价文档仅因包含产品词就错误覆盖成色要求。
2. 扩充披露、包装缺失、配件替代成本、定价因素和来源引用等稳定业务概念，让口语和同义表达进入同一 Evidence Plan。
3. 多跳问题按“产品规则、成色披露、维修/使用史、配件、定价因素、价格忠实度、来源引用”规划互补证据，重排时优先补齐未覆盖需求。
4. 明确价格边界：缺字段本身只要求补证据；只有同时要求直接/精确报价，或明确要求先补字段再估价时，才加入“证据不足不得给确定价格”的知识。
5. Query Rewrite 只在复杂多证据查询上触发，并将外层超时收紧到 `3s`；超时后保留原查询继续检索，避免改写服务阻断 RAG 主链路。

## 4. 验证证据

- 数据集校验：`eval/reports/ragas_dataset_development_v5_validation_report.md`
- Development v5 基线：`eval/reports/ragas_rag_development_v5_baseline_report.md`
- Development v5 冻结：`eval/reports/ragas_rag_development_v5_freeze_report.md`
- Regression 冻结：`eval/reports/ragas_rag_regression_development_v5_freeze_report.md`
- 实现级测试：`KnowledgeEvidencePlannerTest`、`QueryRewriteTriggerTest`、`DeterministicKnowledgeRerankerTest`、`KnowledgeSearchRerankIntegrationTest`、`KeywordSearchServiceImplTest` 全部通过。

## 5. 结论边界

本轮 `LLM judge enabled=false`，因此 Faithfulness、Answer Relevancy、LLM Context Precision 和 LLM Context Recall 没有重新验收。当前结论只覆盖：

- 基于稳定逻辑知识 ID 的检索准确性；
- 逐题 Gold 上下文完整召回；
- Token、fallback 与端到端时延门禁；
- 历史回归不退化。

Development v5 是可见开发集，不代表新问题泛化能力。最终冻结报告中有一个 `5.35s` 的单例尾延迟，P95 仍为 `2.14s`；日志显示其来自 Query Rewrite 模型调用被外层超时取消。项目自身对 Query Rewrite 只尝试一次并回退原查询，Spring AI 内部 `RetryTemplate` 仍会为被中断请求打印一次框架告警；主请求最终正常完成，报告中的 RAG 回答 fallback 率为 `0%`。后续性能优化应优先统一框架内部重试与外层超时策略，而不是牺牲证据覆盖。

## 6. 下一步

1. 冻结当前实现、配置、Gold catalog、Development v5 与 Regression 报告的哈希；
2. 从独立来源编写从未见过的 Holdout v5，并只执行一次 `--summary-only` 盲测；
3. 使用独立 Judge key 对 Holdout v5 开启四项 LLM Judge 指标；
4. 若 Holdout v5 失败，只按聚合失败类型构造 Development v6，不针对保留集题面继续堆规则。
