# Retrieval Eval Report - YYYYMMDD

## 0. 元信息

- 项目：Online Used Marketplace
- 评测日期：YYYY-MM-DD
- 分支：`<branch>`
- Commit：`<commit-hash>`
- 评测人：`<name>`
- 数据集版本：`retrieval_eval_dataset.json@v1`

---

## 1. 实验配置

## 1.1 索引与模型
- 向量库：Milvus
- 关键词检索：Elasticsearch BM25（异常 fallback 到 MySQL MVP）
- Embedding 模型：`<model-name>`
- LLM 模型（用于RAG回答）：`<model-name>`

## 1.2 参数组
- chunkSize: `<value>`
- overlap: `<value>`
- topK: `<value>`
- candidateK: `<value>`
- RRF_K: `<value>`
- 置信度阈值：`HIGH>=...`, `MEDIUM>=...`, `LOW<...`

---

## 2. 样本统计

- 总样本数：`N`
- 风控类：`N1`
- 优化类：`N2`
- 定价类：`N3`
- 模糊/应拒答类：`N4`

---

## 3. 核心指标结果

| 指标 | Vector | Keyword(ES) | Hybrid(RRF) | Baseline(上次) | 变化 |
|---|---:|---:|---:|---:|---:|
| Recall@3 |  |  |  |  |  |
| Recall@5 |  |  |  |  |  |
| Recall@10 |  |  |  |  |  |
| MRR |  |  |  |  |  |
| Citation Hit Rate |  |  |  |  |  |
| Duplicate Rate |  |  |  |  |  |
| Noise Rate(人工) |  |  |  |  |  |
| Reject Accuracy |  |  |  |  |  |
| lowConfidence 占比 |  |  |  |  |  |
| P95 Latency(ms) |  |  |  |  |  |
| ES fallback 触发率 | - |  |  |  |  |

---

## 4. 分类别表现

## 4.1 风控类（RISK）
- Recall@5: 
- MRR:
- 主要误召回模式：
- 典型好例：`caseId=`
- 典型坏例：`caseId=`

## 4.2 优化类（OPT）
- Recall@5:
- MRR:
- 主要误召回模式：

## 4.3 定价类（PRICE）
- Recall@5:
- MRR:
- 主要误召回模式：

## 4.4 模糊拒答类（AMB）
- 应拒答样本数：
- 正确拒答数：
- 误答数：

---

## 5. 失败样本分析（Top 10）

| caseId | 问题 | 期望(gold) | 实际Top3 | 问题归因 | 修复动作 |
|---|---|---|---|---|---|
|  |  |  |  |  |  |
|  |  |  |  |  |  |

归因分类建议：
- A. chunk 切分问题（语义被截断）
- B. 过滤过严/过松
- C. ES 分词与 mapping 不匹配
- D. RRF 融合权重不合理
- E. 置信度阈值偏差
- F. 数据缺失（索引不全）

---

## 6. 调参实验记录

| 实验ID | 参数变更 | Recall@5 | MRR | Citation Hit Rate | P95(ms) | lowConfidence占比 | 结论 |
|---|---|---:|---:|---:|---:|---:|---|
| EXP-01 |  |  |  |  |  |  |  |
| EXP-02 |  |  |  |  |  |  |  |
| EXP-03 |  |  |  |  |  |  |  |

---

## 7. 结论与决策

## 7.1 本轮结论
- 
- 

## 7.2 是否达标（按验收门槛）
- [ ] Recall@5 >= 0.80
- [ ] MRR >= 0.65
- [ ] Citation Hit Rate >= 0.75
- [ ] Duplicate Rate <= 0.15
- [ ] Hybrid P95 <= 1200ms
- [ ] False Accept <= 10%

## 7.3 版本决策
- [ ] 保持当前参数上线灰度
- [ ] 回滚至上一参数组
- [ ] 继续优化后再评测

---

## 8. 下一步行动（Next Actions）

1. 
2. 
3. 
