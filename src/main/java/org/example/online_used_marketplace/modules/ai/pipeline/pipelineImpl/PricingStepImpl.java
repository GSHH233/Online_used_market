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
import org.example.online_used_marketplace.modules.ai.service.PricingSuggestionService;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchHitVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 价格建议步骤
 */
@Service
@Order(3)
@RequiredArgsConstructor
public class PricingStepImpl implements PublishAssistStep {
    private final PricingSuggestionService pricingSuggestionService;
    private final RagSourceMergeHelper ragSourceMergeHelper;
    private final KnowledgeSearchService knowledgeSearchService;
    private final PublishAssistRetrievalTraceHelper retrievalTraceHelper;
    private static final int SOURCE_LIMIT = 10;

    @Override
    public void apply(PublishInputContext context, PublishAssistResult result) {
        ProductPublishAssistRequestDTO dto = new ProductPublishAssistRequestDTO();
        dto.setTitle(context.getTitle());
        dto.setBrand(context.getBrand());
        dto.setDescription(context.getDescription());
//        dto.setCategoryName(context.getCategoryName());
        dto.setConditionText(context.getConditionText());

        String suggestion = pricingSuggestionService.buildPriceSuggestion(dto, result.getRiskScore());
        result.setPriceSuggestion(suggestion);

        String question = context.getTitle()
                + " " + (context.getBrand() != null ? context.getBrand() : "")
                + " " + (context.getDescription() != null ? context.getDescription() : "")
                + " " + (context.getConditionText() != null ? context.getConditionText() : "")
                + " " + (context.getCategoryName() != null ? context.getCategoryName() : "")
                + " 行情区间 定价规则 二手参考价 配件 电池 快门 型号";
        KnowledgeSearchFilterDTO filter=new KnowledgeSearchFilterDTO();
//        filter.setCategory(context.getCategoryName());
        filter.setSourceType(KnowledgeSourceType.MARKET);
        KnowledgeSearchResponseVO response = knowledgeSearchService.search(question, 5, filter);
        List<KnowledgeSearchHitVO> hits = (response == null || response.getHits() == null) ? Collections.emptyList() : response.getHits();
        retrievalTraceHelper.append(result, retrievalTraceHelper.toRetrievalTrace(
                stepKey(),
                "二手行情与定价规则检索",
                result.getPipelineRound(),
                KnowledgeSourceType.MARKET,
                question,
                5,
                response,
                false
        ));
        List<RagSourceVO> incoming = ragSourceMergeHelper.toRagSources(hits);
        List<RagSourceVO> merged = ragSourceMergeHelper.mergeSources(result.getSources(), incoming, SOURCE_LIMIT);
        result.setSources(merged);
    }

    @Override
    public String stepKey() {
        return "pricing";
    }

    @Override
    public String stepLabel() {
        return "价格建议";
    }
}
