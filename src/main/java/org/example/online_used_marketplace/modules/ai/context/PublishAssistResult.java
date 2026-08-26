package org.example.online_used_marketplace.modules.ai.context;

import lombok.Data;
import org.example.online_used_marketplace.modules.ai.vo.AssistStepTraceVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistRetrievalVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.example.online_used_marketplace.modules.ai.vo.RiskEvidenceItemVO;

import java.util.List;

/**
 * @author Admin
 * @ClassName: PublishAssistResult
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-24  0:55
 * @Version:
 */
@Data
public class PublishAssistResult {
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

    private List<RagSourceVO> sources;

    /**
     * 当前执行轮次，仅用于将风险检索观测与 Replan 链路关联。
     */
    private Integer pipelineRound;

    private List<PublishAssistRetrievalVO> retrievals;

    private String generationMode;

    private Boolean fallbackUsed = false;

    private String fallbackReason;
}
