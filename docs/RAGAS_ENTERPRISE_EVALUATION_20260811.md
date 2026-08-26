# RAGAS 企业级 RAG 评测记录（持续迭代）

## 1. 评测协议

- Holdout v1 的逐题结果不参与调参，只抽象出交易安全、定价多约束、拒答依据和多意图四类失败模式。
- 使用这些类型重新编写 24 条可见 Development v2，完成架构修改后再跑 50 条历史 Regression。
- 两级门禁通过后才创建 20 条未见 Holdout v2；数据校验确认 119 条总数据中不存在 Holdout 文本泄漏。
- Holdout v2 使用 `--summary-only` 盲测一次，首次结果冻结，不针对其中单题继续修改实现。

## 2. 架构修改

1. 在 Deterministic Rerank 的 MMR 选择中加入加权 Facet Coverage，低分候选只要能补齐尚未覆盖的业务证据，就不会被 Dynamic TopK 过早截断。
2. ES 关键词检索拆为原始词面通道和业务概念通道，再在通道内使用 RRF 合并，降低长问题中泛词对关键规则的挤压。
3. 将数值声明与价格边界拆开，区分“出现百分比”和“固定折扣是否有依据”。
4. 当基线证据已覆盖确定性意图时跳过 LLM Query Rewrite，保留改写收益同时降低拒答类查询的尾延迟。

## 3. 结果

| 数据集 | Verdict | ID Precision | ID Recall | 完整召回 | Retrieval P95 | E2E P95 |
|---|---|---:|---:|---:|---:|---:|
| Development v2 基线 24 | RISK | 0.4104 | 0.6667 | 14/24 | 4608ms | - |
| Development v2 优化后 24 | PASS | 0.5924 | 0.9583 | 22/24 | 606ms | 3351.8ms |
| Regression 50 | PASS | 0.6667 | 1.0000 | 50/50 | 327ms | 3591.7ms |
| Holdout v2 20（首次盲测） | RISK | 0.4808 | 0.6250 | 11/20 | 315ms | 3429.4ms |

Development v2 和 Regression 说明修改对已抽象问题有效，且没有破坏历史发布门禁；Holdout v2 则证明当前系统对新的交易表达、多约束和多跳问题仍不够稳定。性能门禁已经通过，下一轮重点应从词面概念映射转向更稳健的业务意图表示与证据规划，而不是继续增加当前 Holdout 的同义词。

## 4. 当前结论

当前版本可表述为“已通过开发集和 50 条回归门禁，检索 P95 低于 4 秒”，不能表述为“企业级泛化验收通过”。下一轮应从 Holdout v2 的聚合分组生成来源独立的 Development v3，验证交易安全语义归一化、复合意图拆解和证据覆盖计划；修改完成后重新构造 Holdout v3。

本轮未启用独立 LLM Judge，因此 Faithfulness、Answer Relevancy 等生成指标仍为 N/A；当前结果只代表基于逻辑知识 ID 的检索上下文评估。

## 5. 第三轮：Development v3

本轮没有重跑或查看 Holdout v2 单题，而是只使用其聚合失败类型，重新编写 30 条来源独立的 Development v3：交易安全 12 条、多跳证据 10 条、拒答与依据 8 条。数据集校验共覆盖 149 条，未发现 Holdout 问题文本泄漏。

架构改造包括：

1. 将平台内下单、拆分结算、个人账户付款、账号归属分别归一化，避免把“在平台沟通”等同于“平台担保支付”。
2. 为复合问题生成显式 Evidence Plan，分别表示商品类别、成色披露、维修/使用历史、配件、定价边界、来源引用等证据需求。
3. 根据证据需求动态扩展候选预算，并在 Dynamic TopK 截断前优先选择能补齐未覆盖证据的候选。
4. 增加 noisy 反例约束；“流量卡不实名”不再因“实名”子串误路由到账号所有权规则。

| 数据集 | Verdict | ID Precision | ID Recall | 完整召回 | Retrieval P95 | E2E P95 |
|---|---|---:|---:|---:|---:|---:|
| Development v3 改造前 30 | RISK | 0.4700 | 0.7222 | 18/30 | 5921ms | - |
| Development v3 改造后 30 | PASS | 0.7122 | 0.9722 | 28/30 | 599ms | 3209.6ms |
| Regression 50 复验 | PASS | 0.5943 | 0.9700 | 门禁通过 | 274ms | 3656.6ms |

Development v3 和 Regression 均通过当前发布门禁，但这仍不是新的泛化结论。Holdout v2 保持首次结果不变；三存储 Unicode 审计确认此前乱码只是终端显示问题，下一步创建未见 Holdout v3 并只盲测一次。

## 6. 框架版本决策

当前项目是 Spring Boot 3.5.6 + Spring AI 1.0.3。本轮瓶颈来自业务语义归一化、候选覆盖和重排控制流，不来自 Spring AI API，因此没有在同一实验中迁移框架。Spring AI 2.0 迁移需要同时处理 Spring Boot 4 基线和升级说明中的破坏性变化，应在独立分支完成编译、接口契约、模型连通性和全套评测对照；暂不引入 LangChain4j，避免同一 JVM 服务中出现两套模型抽象与可观测链路。

## 7. 第四轮：Development v4

Holdout v3 首次盲测结果继续冻结。本轮只根据其聚合失败类型构造 30 条 Development v4，未查看、复制或改写 Holdout v3 单题；数据校验覆盖 204 条用例，字符 bigram 泄漏检查无异常。

改造前 Development v4 已满足 Precision 门槛，但多跳证据、拒答依据和同义问法召回不足。实现侧没有继续堆逐题关键词，而是完成以下调整：

1. 扩展交易安全、定价依据、来源引用、规则版本、配件和流量卡等显式 Evidence Plan。
2. RRF 融合后从原始关键词、向量和改写候选池中为每项证据需求保留至少一个匹配候选，避免高分同类文档挤掉互补证据。
3. 对站外交易、账号、标识篡改、烟草、管制器具、医疗和流量卡等强规则语义族做保守去重，减少相邻规则重复占位。
4. 修复 ES 启动回填未写入 `indexVersion` 和 `logicalKnowledgeId` 的问题。此前 ES 虽命中正确文档，但生命周期治理会把缺少版本信息的结果过滤掉。

| 数据集 | Verdict | ID Precision | ID Recall | 完整召回 | Retrieval P95 | E2E P95 |
|---|---|---:|---:|---:|---:|---:|
| Development v4 改造前 30 | RISK | 0.6439 | 0.8778 | 24/30 | 314ms | 3926.8ms |
| Development v4 改造后 30 | PASS | 0.7456 | 1.0000 | 30/30 | 359ms | 3890.6ms |
| Regression 50 最终复验 | PASS | 0.6667 | 1.0000 | 50/50 | 314ms | 4227.5ms |

本轮说明架构调整对可见开发类型有效，且没有破坏 50 条历史回归。Regression 的 E2E P95 略高于 4 秒，但 Retrieval P95 仅 314ms，主要尾延迟来自模型生成；后续性能优化应放在生成预算、流式首字延迟和模型调用稳定性，而不是继续牺牲检索覆盖。当前仍不能宣称泛化验收通过，下一步应冻结代码并新建来源独立的 Holdout v4，只做一次 `--summary-only` 盲测。

## 8. Judge 口径修正与完整 Gold

冻结前将 LLM Judge 从一次综合评分改为四个相互隔离的单指标调用，避免生成质量与检索质量互相污染：

1. Faithfulness 只读取问题、回答与检索证据；“证据未覆盖所问结论，因此无法确定”被视为证据边界声明，不作为外部事实扣分。
2. Answer Relevancy 只读取问题、回答与完整参考答案，检查回答是否直接处理问题以及是否遗漏核心结论。
3. Context Precision 由 Judge 对每个证据块做二元相关性标注，脚本按排名感知 Average Precision 计算，不允许模型自由猜一个小数。
4. Context Recall 只检查逐题原子 `referenceFacts` 的证据覆盖率，不把完整答案中的问题复述、推理连接词和拒答措辞当成必须召回的知识。

Development v4 与新 Holdout v4 均已补齐两层 Gold：`reference` 是完整、可直接判定回答正确性的参考答案；`referenceFacts` 是 Context Recall 使用的最小知识事实。Development v4 共 30 条，两类字段完整率均为 100%。

最终冻结候选结果如下：

| 数据集 | Verdict | Faithfulness | Answer Relevancy | LLM Ctx Precision | LLM Ctx Recall | ID Recall | E2E P95 | Gen P95 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Development v4 30 | PASS | 0.9167 | 0.8500 | 0.9061 | 0.8889 | 1.0000 | 1.71s | 1.54s |
| Regression 50 | PASS | N/A | N/A | N/A | N/A | 1.0000 | 1.72s | 1.51s |

`ragas_release_freeze_v4_manifest.json` 对 19 个实现、配置、Judge、Gold 和报告文件记录 SHA-256。冻结后创建 Holdout v4，数据校验覆盖 234 条用例，未发现字符 bigram 近重复泄漏。

## 9. Holdout v4 一次性盲测

Holdout v4 共 30 条，完整参考答案与原子 Gold Facts 覆盖率均为 100%。执行前在 manifest 中声明 `maximumRuns=1`、`summaryOnly=true`、`postRunTuningAllowed=false`；执行后数据集哈希和实现冻结哈希均验证通过。

| 数据集 | Verdict | Faithfulness | Answer Relevancy | LLM Ctx Precision | LLM Ctx Recall | ID Precision | ID Recall | 完整召回 | E2E P95 | Gen P95 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Holdout v4 30（首次盲测） | RISK | 0.9250 | 0.8500 | 0.8622 | 0.8278 | 0.6556 | 0.8722 | 0.8000 | 1.70s | 1.36s |

生成忠实度、回答相关性、LLM Context 指标、P95、平均 Prompt Token `604.7` 与 fallback `0%` 均通过冻结门槛；失败项是 ID Context Recall 未达到 `0.90`，逐题完整召回通过率未达到 `0.90`。聚合薄弱组为成色披露精度、中等难度改写、多跳证据召回和 paraphrase 召回。

该结果必须表述为“生成质量和性能门禁通过，但新题检索泛化尚未通过发布门禁”，不能表述为 RAG 已完成企业级泛化验收。下一轮只能从上述聚合失败类型建立新的 Development v5，完成架构级修改后再创建来源独立的 Holdout v5；不得读取 Holdout v4 逐题输出、针对其题面堆关键词或重复运行 v4。
