# 发布助手反馈闭环 Spec

状态：`IMPLEMENTED_PENDING_EVAL`  
范围：建议采纳、商品发布关联、风险结果回流  
现有基础：发布助手流水线、风险证据、商品创建接口、JWT 用户身份

## 1. 背景与目标

当前发布助手会生成标题、描述、价格和风险建议，但系统不知道用户是否采用，也无法用后续审核结果判断风险建议是否有效。本 Spec 建立“建议 -> 发布 -> 结果反馈 -> 指标”闭环。

## 2. 核心流程

```text
请求发布建议 -> 生成 assistId 和 traceId
-> 用户应用全部/部分建议
-> 创建商品时关联 assistId
-> 审核、下架或投诉结果回流
-> 统计采纳率、风险命中率和误报率
```

## 3. 功能需求

### PUB-FR-001 建议快照

每次调用现有 `/api/ai/products/publish-assist` 都持久化输入、输出、证据、风险级别、模型版本和 traceId，并返回不可猜测的 `assistId`。

### PUB-FR-002 采纳反馈

用户可标记 `APPLIED`、`PARTIAL`、`IGNORED`，并按字段记录是否采用 `title`、`description`、`price`、`riskAdvice`。用户只能反馈自己的建议。

### PUB-FR-003 商品关联

商品创建请求可选携带 `assistId`。后端校验归属后，将最终发布内容与建议快照关联；不能依赖前端自行回传原始建议。

### PUB-FR-004 风险结果回流

INTERNAL 可记录自动审核结果，ADMIN 可记录人工复核结果：

```text
PENDING / PASSED / REJECTED / TAKEN_DOWN / COMPLAINT_CONFIRMED
```

回流必须包含 outcome source、reason code、operator 和时间。

### PUB-FR-005 业务指标

```text
feedback_coverage = 已反馈建议数 / 已生成建议数
adoption_rate = (APPLIED + PARTIAL) / 已反馈建议数
full_adoption_rate = APPLIED / 已反馈建议数
risk_precision = 被 AI 标记风险且结果确认有风险 / 被 AI 标记风险且已有结果
false_positive_rate = 被 AI 标记风险但结果通过 / 被 AI 标记风险且已有结果
```

分母为 0 时返回 `null`，不能伪造 0%。

## 4. 数据模型

新增 `ai_publish_assist_record`：

| 字段 | 说明 |
| --- | --- |
| assist_id | UUID/字符串业务主键 |
| user_id、trace_id | 用户与链路 |
| request_json、suggestion_json | 输入和建议快照 |
| evidence_json | 风险证据 |
| risk_level | AI 风险等级 |
| model_name、prompt_version | 可追溯版本 |
| created_time | 创建时间 |

新增 `ai_publish_assist_feedback`：

| 字段 | 说明 |
| --- | --- |
| assist_id、product_id、user_id | 关联关系 |
| adoption_type | APPLIED / PARTIAL / IGNORED |
| adopted_fields_json | 字段级采纳情况 |
| final_content_hash | 最终内容摘要 |
| feedback_reason | 可选原因 |

新增 `ai_product_risk_outcome`：记录商品审核、下架和投诉结果。一个商品允许多次结果，但通过 `is_final` 标记最终结果。

## 5. API 契约

现有发布助手响应增加 `assistId` 和 `traceId`。

```http
POST /api/ai/products/publish-assist/{assistId}/feedback
GET  /api/ai/products/publish-assist/{assistId}
POST /api/ai/products/{productId}/risk-outcomes
GET  /api/ai/products/publish-assist/metrics?from=&to=
```

反馈请求示例：

```json
{
  "adoptionType": "PARTIAL",
  "adoptedFields": ["title", "riskAdvice"],
  "productId": 101,
  "reason": "价格仍按卖家预期填写"
}
```

## 6. 安全与隐私

- USER 只能读取和反馈自己的 assistId。
- INTERNAL 只能写自动审核结果；人工最终结论和修正需要 ADMIN。
- 指标接口只返回聚合值，不暴露用户输入和商品隐私。
- 建议快照不可被客户端覆盖；反馈采用 upsert 并保留更新时间。

## 7. 验收标准

### PUB-AC-001 建议可追踪

Given 用户请求发布助手，When 返回建议，Then 响应包含 assistId/traceId，数据库中输入、输出、证据与响应一致。

### PUB-AC-002 字段级采纳

Given 用户仅采用标题，When 提交 PARTIAL，Then adoptedFields 只包含 title，并与商品正确关联。

### PUB-AC-003 越权保护

Given 其他用户的 assistId，When 查询或反馈，Then 返回 403 或 404，不能泄露记录是否存在。

### PUB-AC-004 风险回流

Given AI 标记高风险商品，When ADMIN 回流 REJECTED，Then该样本进入 risk precision 的正确分子与分母。

### PUB-AC-005 指标正确

Given 固定的采纳和结果样本，When 查询指标，Then五项指标与离线计算一致。

## 8. 评测映射

新增 `publish_feedback_eval_dataset.json` 与 `publish_feedback_eval.py`，至少 20 条，覆盖快照、关联、采纳、越权、重复反馈、结果回流和指标计算。
