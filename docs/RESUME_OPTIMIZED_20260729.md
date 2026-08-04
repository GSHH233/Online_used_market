# 简历优化稿（2026-07-29）

## 专业技能

- 熟悉 Java 核心知识、集合、并发与 JVM，能够使用 Spring Boot、MyBatis-Plus 完成后端模块设计及 RESTful API 开发。
- 熟悉 Spring AI、RAG 与 Agent 应用开发，具备文档解析切片、向量检索、混合召回、工具调用、流式回答和自动化评测实践。
- 熟悉 MySQL、Redis、Elasticsearch、Milvus 与 RabbitMQ，了解缓存设计、消息异步处理、检索索引及多存储数据一致性问题。
- 熟悉 Git、Docker Compose、Python 评测脚本与 Prometheus，能够完成本地环境搭建、接口调试、指标采集及问题回归。

## 项目经历

### 回血集市 - AI 商品发布与智能运维助手

**2025.11.14 - 至今　｜　项目角色：独立开发**

**项目描述：** 面向二手电商平台，提供 AI 商品发布、交易规则问答、知识库管理和内部智能排查能力。

**技术架构：** Spring Boot + Spring AI + Vue3 + MySQL + Redis + Elasticsearch + Milvus + RabbitMQ + Prometheus

- **AI 商品发布助手：** 将 AI 嵌入商品发布流程，根据标题、成色和凭证生成文案、风险提示及规则证据；支持流式输出、建议采纳、证据不足拒答和价格防编造，Source Hit 与拒答准确率均达到 96.83%。
- **智能知识检索：** 针对口语化问法与文档术语不一致，引入动态 Query Rewrite，融合 Milvus 向量召回与 Elasticsearch 关键词检索，并使用 RRF 排序；相较纯关键词检索 Recall@5 提升 6.56%，61 条 hard case 中 Recall@5 达到 100%、MRR 为 0.93。
- **知识库管理：** 支持 Markdown、PDF、Word 解析和结构化切片，通过 RabbitMQ 异步执行入库任务；使用统一 chunkId 对齐 DB、ES、Milvus，新版本校验后再生效，抽样 20 个文档三存储一致率达到 100%。
- **智能运维排查：** 提供统一内部排查入口，自动汇总日志、Prometheus 和只读数据库证据；高风险修复需 ADMIN 审批，执行后重新验证，失败转人工，24 条 Agent 与 16 条修复闭环用例通过。

### 凌霄优仓 - 商铺管理与营销系统

**2025.03 - 2025.06　｜　项目角色：独立开发**

**项目描述：** 面向外卖服务平台，实现商户管理、优惠券秒杀、内容 Feed 和缓存治理等功能。

**技术架构：** Spring Boot + MyBatis-Plus + MySQL + Redis + Elasticsearch + WebSocket + Nginx

- **优惠券秒杀：** 使用 Redis Lua 将库存校验、一人一券、库存扣减和 Stream 写入封装为原子操作，异步削峰后写入 MySQL，并通过唯一索引与异常消息兜底，避免超卖和重复领取。
- **商铺内容流：** 基于 Redis Set 维护用户关注关系，使用 Redis Stream 异步扇出商户动态，并通过 Elasticsearch 完成商铺和内容检索，降低关注关系与内容查询的耦合。
- **缓存一致性：** 针对商铺热点数据设计逻辑过期、延迟双删和空值缓存，结合定时扫描、Stream 补偿与 Outbox 记录处理失败任务，减少缓存击穿并保证最终一致性。

## 个人总结

- 具备 Java 后端与 AI 应用复合开发经验，能够独立完成需求拆解、接口设计、编码实现、自动化评测和问题回归。
- 重视功能边界和可验证结果，能够通过固定数据集、Trace、Recall/MRR、P95 和用例通过率定位 AI 链路问题。
- 具备较强的文档阅读和问题排查能力，能够快速学习新框架并结合现有工程完成落地。

## 面试讲述顺序（不放入简历）

主项目只主动讲四个功能：

```text
AI 商品发布助手
-> 智能知识检索
-> 知识库管理
-> 智能运维排查
```

技术深挖映射：

| 面试官追问 | 再展开的技术 |
| --- | --- |
| 检索为什么更准 | Query Rewrite、Hybrid、RRF、metadata、Recall/MRR |
| 文档更新失败怎么办 | chunkId、STAGING/ACTIVE、三存储校验、RabbitMQ、Repair |
| Agent 为什么不会乱调用 | 工具白名单、USER/INTERNAL/ADMIN、审批、参数快照 |
| 工具超时或失败怎么办 | Harness、超时重试、Redis 快照、fallback |
| 长任务中断怎么办 | Runtime、Checkpoint、幂等、恢复与重放 |
| 怎么证明不是 Demo | 固定数据集、Trace、评测脚本、P95、失败回归 |
