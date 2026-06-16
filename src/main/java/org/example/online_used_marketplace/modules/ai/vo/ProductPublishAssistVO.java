package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.List;

/**
 * @author Admin
 * @ClassName: ProductPublishAssistVO
 * @Description: 发布助手聚合返回结果
 * @date 2026-04-21  19:34
 * @Version:
 */
@Data
public class ProductPublishAssistVO {
    /**
     * 文案优化结果
     */
    private ProductOptimizeResponseVO optimizeResult;

    /**
     * 风险评分（0-100，分数越高风险越低）
     */
    private Integer riskScore;

    /**
     * 风险说明（聚合层可单独补充，允许为空）
     */
    private List<String> riskWarnings;

    /**
     * 价格建议文案
     */
    private String priceSuggestion;

    /**
     * 是否建议发布
     */
    private Boolean canPublish;

    private List<AssistStepTraceVO> trace;

    private List<RiskEvidenceItemVO> riskEvidence;

    /**
     * RAG检索来源（与riskEvidence区分）
     */
    private List<RagSourceVO> sources;
}