package org.example.online_used_marketplace.modules.ai.pipeline.pipelineImpl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.context.PublishAssistResult;
import org.example.online_used_marketplace.modules.ai.context.PublishInputContext;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.pipeline.PublishAssistStep;
import org.example.online_used_marketplace.modules.ai.pipeline.PublishAssistRetrievalTraceHelper;
import org.example.online_used_marketplace.modules.ai.pipeline.RagSourceMergeHelper;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.RiskAssessmentService;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchHitVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.example.online_used_marketplace.modules.ai.vo.RiskEvidenceItemVO;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 风险评估步骤
 */
@Service
@Order(2)
@RequiredArgsConstructor
public class RiskStepImpl implements PublishAssistStep {

    private static final int SOURCE_LIMIT = 10;

    private final RiskAssessmentService riskAssessmentService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final RagSourceMergeHelper ragSourceMergeHelper;
    private final PublishAssistRetrievalTraceHelper retrievalTraceHelper;

    @Override
    public void apply(PublishInputContext context, PublishAssistResult result) {
        ProductPublishAssistRequestDTO dto = new ProductPublishAssistRequestDTO();
        dto.setTitle(context.getTitle());
        dto.setBrand(context.getBrand());
        dto.setDescription(context.getDescription());
        dto.setConditionText(context.getConditionText());
//        dto.setCategoryName(context.getCategoryName());

        List<RiskEvidenceItemVO> evidence = riskAssessmentService.buildRiskEvidence(dto, result.getOptimizeResult());

        // --- RAG RULE 检索 ---
        String question = context.getTitle() + " " + context.getDescription() + "违规规则 风险判断";
        KnowledgeSearchFilterDTO filter = new KnowledgeSearchFilterDTO();
        filter.setSourceType(KnowledgeSourceType.RULE);
        KnowledgeSearchResponseVO response = knowledgeSearchService.search(question, 5, filter);
        List<KnowledgeSearchHitVO> hits = (response == null || response.getHits() == null)
                ? Collections.emptyList() : response.getHits();
        retrievalTraceHelper.append(result, retrievalTraceHelper.toRetrievalTrace(
                stepKey(),
                "发布风险规则检索",
                result.getPipelineRound(),
                KnowledgeSourceType.RULE,
                question,
                5,
                response,
                false
        ));

        // RAG 命中只用于解释规则来源。是否阻断必须由原始发布文本的确定性规则命中决定，
        // 否则召回一篇包含“禁止”的通用规则文档就会误伤正常商品。
        List<RagSourceVO> incoming = ragSourceMergeHelper.toRagSources(hits);
        List<RagSourceVO> merged = ragSourceMergeHelper.mergeSources(result.getSources(), incoming, SOURCE_LIMIT);
        result.setSources(merged);

        // 重新计算风险分与警告（含 RAG 证据）
        Integer riskScore = riskAssessmentService.calculateRiskScoreByEvidence(evidence);
        List<String> riskWarnings = riskAssessmentService.buildRiskWarnings(result.getOptimizeResult(), riskScore);

        result.setRiskEvidence(evidence);
        result.setRiskScore(riskScore);
        result.setRiskWarnings(riskWarnings);

    }

    @Override
    public String stepKey() {
        return "risk";
    }

    @Override
    public String stepLabel() {
        return "风险评估";
    }
}
