# 五分钟项目演示清单

## 演示前准备

- Spring Boot、前端、MySQL、PostgreSQL、Redis、RabbitMQ、ES、Milvus、Prometheus 均可访问。
- 准备 USER、INTERNAL、ADMIN 三个账号，不在文档或命令历史中保存真实 Token。
- 确认 docId 19 可检索且 DB/ES/Milvus chunkId 一致。
- 提前打开商城、RAG 对话、智能排查、任务运行、知识缺口和存储一致性页面。

## 0:00-1:00 商品与可信 RAG

1. USER 登录并浏览商品。
2. 在 RAG 对话询问“二手手机标题写全新未拆封、无发票有风险吗”。
3. 展示流式回答、证据来源、风险提示；说明证据不足时拒答且不编造价格。

## 1:00-2:00 混合检索与知识缺口

1. 展示向量召回、ES 关键词召回及 RRF 融合结果。
2. 提交一个知识库无法回答的问题。
3. INTERNAL 打开知识缺口列表，展示低置信度问题沉淀、关联文档和回归状态。

## 2:00-3:10 智能排查与 Runtime

1. INTERNAL 输入需要日志、指标和数据库共同判断的问题。
2. 展示后端选择 DIRECT/REACT/DAG，前端只呈现结论、证据和执行轨迹。
3. 打开“任务运行”，展示 Run、节点、checkpoint 和 traceId。
4. 说明恢复会跳过已成功节点，快照重放不会再次调用真实工具。

## 3:10-4:10 受控修复闭环

1. INTERNAL 创建单文档 Repair 计划，状态为 `PENDING_APPROVAL`。
2. 说明 INTERNAL 只能诊断和提交，ADMIN 才能审批执行。
3. ADMIN 审批并执行，展示执行、三存储验证、成功关闭；失败则 `ESCALATED` 转人工。
4. 展示旧 Repair/Runbook 真执行入口返回 409，不能绕过审批。

## 4:10-5:00 可靠性与量化结果

1. 展示 Harness 的超时、重试、Redis 快照和 fallback 故障注入报告。
2. 展示 Retrieval、RAG Chat、Agent、AIOps、存储一致性和 Runtime 报告。
3. 用一句话收口：项目形成“可检索、可编排、可审批、可恢复、可验证、可回归”的工程闭环。

## 演示失败预案

- LLM 不可用：展示已有报告和 Trace，不伪造在线成功结果。
- ES/Milvus 不可用：展示健康状态和 fallback 标识，正式指标不计入降级结果。
- Repair 数据已一致：使用 forceReindex 创建受控计划，或展示最近成功任务，不临时破坏数据。
- Runtime 太快无法暂停：展示已完成 Run 的 checkpoint 与快照重放；暂停恢复用自动化报告证明。
