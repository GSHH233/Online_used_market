package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.context.PublishAssistResult;
import org.example.online_used_marketplace.modules.ai.context.PublishInputContext;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.pipeline.pipelineImpl.DecisionStepImpl;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;
import org.example.online_used_marketplace.modules.ai.vo.RiskEvidenceItemVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishRiskBusinessRulesTest {

    private final RiskAssessmentServiceImpl riskService = new RiskAssessmentServiceImpl();

    @Test
    void shouldMatchHardRiskRulesFromOriginalDescription() {
        List<String> codes = evidenceCodes("确定购买后先付款，直接微信转账，不走平台");

        assertTrue(codes.contains("HIGH_PREPAY"));
        assertTrue(codes.contains("HIGH_WECHAT_PAY"));
    }

    @Test
    void shouldIgnoreNegatedAndPolicyQuotedRiskTerms() {
        assertFalse(evidenceCodes("仅支持平台担保交易，不接受微信转账").contains("HIGH_WECHAT_PAY"));
        assertFalse(evidenceCodes("不是盗抢机，购买记录可以提供").contains("HIGH_STOLEN_DEVICE"));
        assertFalse(evidenceCodes("平台规则明确禁止私下交易，我只接受平台担保")
                .contains("HIGH_PRIVATE_TRADE"));
    }

    @Test
    void shouldStillMatchLaterNonNegatedOccurrence() {
        assertTrue(evidenceCodes("平台规则禁止私下交易，但联系后可以私下交易")
                .contains("HIGH_PRIVATE_TRADE"));
    }

    @Test
    void shouldDeduplicateAliasesForSameRule() {
        List<String> codes = evidenceCodes("无售后，也没有任何售后服务");

        assertTrue(codes.contains("HIGH_NO_AFTER_SALE"));
        assertTrue(codes.stream().filter("HIGH_NO_AFTER_SALE"::equals).count() == 1);
    }

    @Test
    void shouldBlockSingleDeterministicHighRiskEvidence() {
        ReplanServiceImpl replanService = new ReplanServiceImpl();
        DecisionStepImpl decisionStep = new DecisionStepImpl(replanService);
        PublishAssistResult result = new PublishAssistResult();
        List<RiskEvidenceItemVO> evidence = riskService.buildRiskEvidence(
                request("这是一台盗抢机"), new ProductOptimizeResponseVO());
        result.setRiskEvidence(evidence);
        result.setRiskScore(riskService.calculateRiskScoreByEvidence(evidence));

        decisionStep.apply(new PublishInputContext(), result);

        assertFalse(result.getCanPublish());
    }

    @Test
    void shouldNotReplanOnlyBecauseRiskDecisionBlocksPublish() {
        ProductPublishAssistVO result = new ProductPublishAssistVO();
        ProductOptimizeResponseVO optimized = new ProductOptimizeResponseVO();
        optimized.setOptimizedTitle("低价有锁二手手机");
        optimized.setOptimizedDescription("这台手机是有锁机，目前不能正常激活，按现状出售。");
        optimized.setTags(List.of("二手手机"));
        result.setOptimizeResult(optimized);
        result.setPriceSuggestion("建议参考平台同类商品价格");
        result.setRiskScore(80);
        result.setCanPublish(false);

        assertFalse(new ReplanServiceImpl().shouldReplan(result));
    }

    private List<String> evidenceCodes(String description) {
        return riskService.buildRiskEvidence(request(description), new ProductOptimizeResponseVO())
                .stream()
                .map(RiskEvidenceItemVO::getRuleCode)
                .toList();
    }

    private ProductPublishAssistRequestDTO request(String description) {
        ProductPublishAssistRequestDTO request = new ProductPublishAssistRequestDTO();
        request.setTitle("二手商品出售");
        request.setDescription(description);
        return request;
    }
}
