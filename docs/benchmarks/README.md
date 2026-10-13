# 冻结评测摘要

本目录只保存适合 GitHub 展示的脱敏摘要。原始运行报告位于本地 `eval/reports/`，默认不提交；数据集、评测脚本和门槛配置保留在仓库中，面试时可以现场复跑。

## 统计原则

- 开发集用于迭代，Holdout 用于盲测，不能针对 Holdout 单题继续调参。
- 固定数据集通过率只描述该样本，不外推为线上 SLA。
- 模型不可用时的 fallback 只验收业务连续性，不计入 LLM 回答质量。
- P95 来自本地单机环境，只用于版本间比较。

## RAG

| 数据集 | 样本 | 结果 | 结论 |
|---|---:|---|---|
| Development v5 | 32 | 完整召回 32/32；ID Context Recall 1.000；Precision 0.6599；E2E P95 2.14s | 开发门禁通过 |
| Regression | 50 | 完整召回 50/50；ID Context Recall 1.000；E2E P95 1.48s | 历史能力未回退 |
| Holdout v4 | 30 | ID Context Recall 0.8722；逐题完整召回 0.8000 | `RISK`，未知问法泛化仍需提升 |
| 首次 Holdout v6（2026-09-21） | 30 | ID Context Recall 0.7556；Precision 0.5439；逐题完整召回 0.6333；Judge=false | `RISK`，未达门槛；后续复跑不能继续称盲测 |

- 数据集：[eval/datasets](../../eval/datasets/README.md)
- 执行脚本：[tools/ragas_rag_eval.py](../../tools/ragas_rag_eval.py)
- 冻结协议：[ragas_release_freeze_v4_manifest.json](../../eval/datasets/ragas_release_freeze_v4_manifest.json)

## Agent 与受控执行

| 链路 | 样本 | 结果 | 验收重点 |
|---|---:|---|---|
| DeepEval Agent | 8 | 工具正确性 1.0；最小权限 1.0 | 工具选择和权限边界；未启用 Task Completion Judge |
| Planner/Replan live | 13 | 13/13；4 条 LLM_STRUCTURED、2 条有限 Replan | 候选工具、权限、步数和实际模型规划路径 |
| AIOps 持续发现 | 27 | 27/27，P95 约 525ms | 告警去重、证据调查、计划生成与闭环状态 |
| 修复闭环 | 16 | 16/16 | 审批、执行后验证、失败转人工和 MTTR |
| Durable Runtime | 15 | 15/15；恢复成功率 100%；重复副作用 0 | checkpoint、恢复、幂等和重放 |
| Harness 故障注入 | 7 | 7/7 | 超时、重试、fallback 和 Redis 快照恢复 |

- DeepEval 脚本：[tools/deepeval_agent_eval.py](../../tools/deepeval_agent_eval.py)
- AIOps 脚本：[tools/aiops_continuous_detection_eval.py](../../tools/aiops_continuous_detection_eval.py)
- Runtime 脚本：[tools/durable_runtime_eval.py](../../tools/durable_runtime_eval.py)

## 知识库治理

| 链路 | 样本 | 结果 | 验收重点 |
|---|---:|---|---|
| 文档入库 | 20 | 20/20，P95 2.324s | Markdown/PDF/DOCX、长短文档、非法输入和幂等 |
| 三存储一致性 | 抽样 20 个文档 | DB/ES/Milvus chunkId 一致率 100% | 统一身份、缺失项定位和 Repair 后复验 |
| 隔离 Docker Runbook | 10 JUnit + 7 实机 API | 10/10 + 7/7 | 仅操作白名单演示容器，健康检查失败后回滚 |

- 入库脚本：[tools/ingestion_chunk_eval.py](../../tools/ingestion_chunk_eval.py)
- 一致性脚本：[tools/storage_consistency_eval.py](../../tools/storage_consistency_eval.py)
- Runbook 脚本：[tools/docker_runbook_eval.py](../../tools/docker_runbook_eval.py)

## 如何复核

2026-10-13 架构修复完整 Java 回归：310 条，308 通过、2 条条件跳过、0 失败；前端构建通过。真实 API 与基础设施联调仍单独验收，不用单测替代；见 [修复追踪表](../specs/architecture-hardening-review-and-repair.md)。

完整命令见 [EVAL_SCRIPT_COMMANDS.md](../EVAL_SCRIPT_COMMANDS.md)。先执行数据集结构校验：

```powershell
python tools/ragas_dataset_validate.py
```

评测报告会写入 `eval/reports/`。提交新的指标前，应同时记录数据集版本、实现提交、模型/Judge 配置和 fallback 比例。
