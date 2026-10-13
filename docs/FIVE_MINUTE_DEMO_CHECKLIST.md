# 五分钟项目演示清单

这份清单只演示一条连续故事：**用户遇到发布/规则问题，内部人员定位知识链路异常，管理员审批修复并验证结果**。Runtime、Memory Graph、独立 ReAct/DAG 页面只在追问时展示。

## 演示前 5 分钟

1. 启动 Spring Boot、Vue 和项目依赖。
2. USER、INTERNAL、ADMIN Token 通过环境变量提供，不写入文档或命令历史：

   ```powershell
   $env:DEMO_USER_TOKEN = '<user-token>'
   $env:DEMO_INTERNAL_TOKEN = '<internal-token>'
   $env:DEMO_ADMIN_TOKEN = '<admin-token>'
   ```

3. 执行只读预检：

   ```powershell
   python tools/demo_preflight.py --base-url http://localhost:8080 --frontend-url http://localhost:5173 --doc-id 19 --strict
   ```

4. 准备固定问题：`二手手机标题写“全新未拆封但无发票”有风险吗？`
5. 确认固定知识文档可用，不在演示时临时删除索引或破坏业务容器。

## 0:00-1:20 用户发布

1. 使用 USER 登录，进入“发布”。
2. 填写标题、描述、成色和价格，点击“检查当前文案”。
3. 展示发布结论、风险原因、引用规则和可套用文案。
4. 采用一项建议并发布，说明 `assistId` 随业务请求自动回传，用户不需要查询数据库。

讲述重点：AI 不是单独的聊天页面，而是嵌入真实发布流程；用户输入事实优先，模型不得编造价格或平台规则。

## 1:20-2:20 可信规则问答

1. 进入“规则助手”，提交同一问题。
2. 展示流式回答和可展开的参考规则。
3. INTERNAL 可以展开“检索过程”；USER 看不到 trace、RRF 分数和工具细节。
4. 说明证据不足时系统拒答，并将低置信度问题沉淀为知识缺口。

讲述重点：Milvus 负责语义召回，Elasticsearch 负责强关键词匹配，RRF 融合两路排名；命中不等于可用，只有 ACTIVE 来源才能进入回答。

## 2:20-4:20 智能排查与受控修复

1. 使用 INTERNAL 进入“智能排查”，选择“知识检索无结果”固定案例。
2. 展示排查结论、关键证据和 traceId；执行策略放在技术详情中。
3. 根据结论创建知识文档 Repair 计划，状态应为“待审批”，此时没有写操作。
4. 切换 ADMIN，审批后再次确认执行。
5. 展示 DB/ES/Milvus 一致性验证以及最终 `RESOLVED`；验证失败时应为 `ESCALATED`，不能伪装成功。

讲述重点：INTERNAL 只能调查和提交计划，ADMIN 才能批准；API 接受固定动作与白名单目标，不接受任意 shell 命令。

## 4:20-5:00 指标与边界

1. 打开“运营总览”，展示发布、检索、知识治理和智能排查四组业务指标。
2. 打开 [评测摘要](benchmarks/README.md)，说明开发集与 Holdout 分离。
3. 用一句话收口：

   > 项目把二手交易中的发布风险和规则知识做成可信 RAG，并把内部知识故障处理做成可调查、可审批、可验证、可回归的 Agent 闭环。

4. 主动说明边界：这是单业务域 AIOps MVP，不是通用 SRE 平台；Holdout v4 Context Recall 为 `0.872`，仍不能宣称生产级未知问题泛化。

## 追问备用

- 为什么使用 RRF：Milvus 相似度与 BM25 分数尺度不同，按排名融合更稳定。
- 为什么需要稳定 chunkId：它是 DB、ES、Milvus 去重、引用和 Repair 的共同身份。
- ReAct 和 DAG 的关系：统一 Planner 选择执行策略；简单任务直接执行，需要多工具依赖时才使用 DAG 并行。
- Harness 做什么：保护工具执行的超时、有限重试、快照恢复和降级，不负责业务决策。
- Runtime 如何恢复：从 checkpoint 恢复并跳过成功节点，幂等键阻止重复副作用。

## 演示失败预案

- 模型额度不可用：明确展示规则降级标识，只证明业务闭环，不把降级结果计入 LLM 质量指标。
- ES 或 Milvus 不可用：展示健康状态与 fallback，不把降级召回当作混合检索成功。
- Repair 数据已经一致：展示最近任务或使用受控 `forceReindex` 计划，不临时破坏生产依赖。
- 外部基础设施异常：展示冻结评测摘要和 trace，不能伪造在线成功结果。
