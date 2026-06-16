package org.example.online_used_marketplace.modules.ai.pipeline.pipelineImpl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.context.PublishAssistResult;
import org.example.online_used_marketplace.modules.ai.context.PublishInputContext;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.enums.RiskLevel;
import org.example.online_used_marketplace.modules.ai.pipeline.PublishAssistStep;
import org.example.online_used_marketplace.modules.ai.pipeline.RagSourceMergeHelper;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.RiskAssessmentService;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchHitVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.example.online_used_marketplace.modules.ai.vo.RiskEvidenceItemVO;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

        // 检查 RULE 命中是否涉及高风险/禁售内容，追加证据
        evidence.addAll(buildRagRuleRiskEvidence(hits));

        // 合并来源
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

    /**
     * 命中规则库中禁售/高风险内容的证据构建
     */
    private List<RiskEvidenceItemVO> buildRagRuleRiskEvidence(List<KnowledgeSearchHitVO> hits) {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        List<RiskEvidenceItemVO> result = new ArrayList<>();
        for (KnowledgeSearchHitVO hit : hits) {
            if (hit == null) continue;
            String text = (hit.getDocTitle() != null ? hit.getDocTitle() : "")
                    + " " + (hit.getSnippet() != null ? hit.getSnippet() : "");
            String lower = text.toLowerCase();
            for (String signal : PROHIBITED_RULE_SIGNALS) {
                if (lower.contains(signal)) {
                    RiskEvidenceItemVO item = new RiskEvidenceItemVO();
                    item.setRuleCode("RAG_RULE_HIGH_RISK");
                    item.setSourceField("ragRuleHit");
                    item.setDeductScore(30);
                    String hitText = hit.getSnippet() != null
                            ? hit.getSnippet().substring(0, Math.min(80, hit.getSnippet().length()))
                            : hit.getDocTitle();
                    item.setHitText(hitText);
                    item.setMessage("规则库匹配到高风险/禁售内容");
                    item.setRiskLevel(RiskLevel.HIGH);
                    result.add(item);
                    break;
                }
            }
        }
        return result;
    }

    private static final List<String> PROHIBITED_RULE_SIGNALS = List.of(
            "禁售", "违禁", "禁止销售", "不得发布",
            "假货", "假冒", "仿品", "盗版",
            "高风险品类", "专项治理"
    );

    @Override
    public String stepKey() {
        return "risk";
    }

    @Override
    public String stepLabel() {
        return "风险评估";
    }
}
