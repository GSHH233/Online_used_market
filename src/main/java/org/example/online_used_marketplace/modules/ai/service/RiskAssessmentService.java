package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.RiskEvidenceItemVO;

import java.util.List;

/**
 * @author Admin
 * @ClassName: RiskAssessmentService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-23  14:33
 * @Version:
 */
public interface RiskAssessmentService {
    List<String> buildRiskWarnings(ProductOptimizeResponseVO optimizeResult, Integer riskScore);

    List<RiskEvidenceItemVO> buildRiskEvidence(ProductPublishAssistRequestDTO request, ProductOptimizeResponseVO optimizeResult);

    Integer calculateRiskScoreByEvidence(List<RiskEvidenceItemVO> evidence);
}
