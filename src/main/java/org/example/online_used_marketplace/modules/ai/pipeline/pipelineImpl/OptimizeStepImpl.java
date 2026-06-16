package org.example.online_used_marketplace.modules.ai.pipeline.pipelineImpl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.context.PublishAssistResult;
import org.example.online_used_marketplace.modules.ai.context.PublishInputContext;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.dto.ProductOptimizeRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.pipeline.PublishAssistStep;
import org.example.online_used_marketplace.modules.ai.pipeline.RagSourceMergeHelper;
import org.example.online_used_marketplace.modules.ai.service.AiProductService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchHitVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 文案优化步骤
 */
@Service
@Order(1)
@RequiredArgsConstructor
public class OptimizeStepImpl implements PublishAssistStep {
    private static final int SOURCE_LIMIT = 10;

    private final AiProductService aiProductService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final RagSourceMergeHelper ragSourceMergeHelper;

    @Override
    public String stepKey() {
        return "optimize";
    }

    @Override
    public String stepLabel() {
        return "文案优化";
    }

    @Override
    public void apply(PublishInputContext context, PublishAssistResult result) {
        ProductOptimizeRequestDTO dto = new ProductOptimizeRequestDTO();
        dto.setTitle(context.getTitle());
        dto.setBrand(context.getBrand());
        dto.setDescription(context.getDescription());
//        dto.setCategoryName(context.getCategoryName());
        dto.setConditionText(context.getConditionText());

        ProductOptimizeResponseVO vo;
        try {
            vo = aiProductService.optimizeProductInfo(dto);
        } catch (Exception ex) {
            vo = buildFallbackOptimizeResult(context);
        }
        result.setOptimizeResult(vo);

        String question =context.getTitle()+context.getBrand()+"成色描述模板 合规文案";
        KnowledgeSearchFilterDTO filter=new KnowledgeSearchFilterDTO();
//        filter.setCategory(context.getCategoryName());
        filter.setSourceType(KnowledgeSourceType.GUIDE);
        KnowledgeSearchResponseVO response = knowledgeSearchService.search(question, 5, filter);
        List<KnowledgeSearchHitVO> hits = (response == null || response.getHits() == null) ? Collections.emptyList() : response.getHits();
        List<RagSourceVO> incoming = ragSourceMergeHelper.toRagSources(hits);
        List<RagSourceVO> merged = ragSourceMergeHelper.mergeSources(result.getSources(), incoming, SOURCE_LIMIT);

        result.setSources(merged);
    }

    private ProductOptimizeResponseVO buildFallbackOptimizeResult(PublishInputContext context) {
        ProductOptimizeResponseVO vo = new ProductOptimizeResponseVO();
        String title = context.getTitle();
        String description = context.getDescription();
        vo.setOptimizedTitle((title == null || title.isBlank()) ? "二手商品待补充信息" : title.trim());
        vo.setOptimizedDescription(
                ((description == null || description.isBlank()) ? "商品信息不足。" : description.trim())
                        + " 请补充商品名称、品牌型号、成色、配件、维修史、凭证和实拍图后再发布。"
        );
        vo.setTags(List.of("信息待补充", "平台交易"));
        vo.setRiskWarnings(List.of("商品信息不足，无法准确判断发布风险或价格"));
        return vo;
    }
}
