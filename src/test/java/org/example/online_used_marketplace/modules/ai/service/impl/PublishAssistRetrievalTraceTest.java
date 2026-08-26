package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.context.PublishAssistResult;
import org.example.online_used_marketplace.modules.ai.context.PublishInputContext;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.enums.RetrievalMode;
import org.example.online_used_marketplace.modules.ai.pipeline.PublishAssistRetrievalTraceHelper;
import org.example.online_used_marketplace.modules.ai.pipeline.RagSourceMergeHelper;
import org.example.online_used_marketplace.modules.ai.pipeline.pipelineImpl.OptimizeStepImpl;
import org.example.online_used_marketplace.modules.ai.pipeline.pipelineImpl.PricingStepImpl;
import org.example.online_used_marketplace.modules.ai.pipeline.pipelineImpl.RiskStepImpl;
import org.example.online_used_marketplace.modules.ai.service.AiProductService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.PricingSuggestionService;
import org.example.online_used_marketplace.modules.ai.service.RiskAssessmentService;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchHitVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistRetrievalVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishAssistRetrievalTraceTest {

    @Test
    void riskStepShouldExposeHybridRetrievalObservation() {
        RiskAssessmentService riskAssessmentService = mock(RiskAssessmentService.class);
        KnowledgeSearchService knowledgeSearchService = mock(KnowledgeSearchService.class);
        RiskStepImpl step = new RiskStepImpl(
                riskAssessmentService,
                knowledgeSearchService,
                new RagSourceMergeHelper(),
                new PublishAssistRetrievalTraceHelper()
        );
        KnowledgeSearchResponseVO response = hybridResponse();
        when(knowledgeSearchService.search(anyString(), eq(5), any(KnowledgeSearchFilterDTO.class)))
                .thenReturn(response);
        when(riskAssessmentService.buildRiskEvidence(any(ProductPublishAssistRequestDTO.class), any(ProductOptimizeResponseVO.class)))
                .thenReturn(List.of());
        when(riskAssessmentService.calculateRiskScoreByEvidence(any())).thenReturn(92);
        when(riskAssessmentService.buildRiskWarnings(any(ProductOptimizeResponseVO.class), eq(92)))
                .thenReturn(List.of());

        PublishInputContext context = new PublishInputContext();
        context.setTitle("二手 RTX3060 显卡");
        context.setDescription("正常自用，无维修，支持平台交易。");
        PublishAssistResult result = new PublishAssistResult();
        result.setPipelineRound(2);
        result.setOptimizeResult(new ProductOptimizeResponseVO());

        step.apply(context, result);

        assertNotNull(result.getRetrievals());
        assertEquals(1, result.getRetrievals().size());
        PublishAssistRetrievalVO retrieval = result.getRetrievals().get(0);
        assertEquals(2, retrieval.getRound());
        assertEquals("risk", retrieval.getStage());
        assertEquals("RULE", retrieval.getSourceType());
        assertEquals("hybrid", retrieval.getRetrievalMode());
        assertEquals(3, retrieval.getVectorHitCount());
        assertEquals(2, retrieval.getKeywordHitCount());
        assertEquals(1, retrieval.getHitCount());
        assertTrue(retrieval.getQuery().contains("违规规则"));
        assertFalse(retrieval.getFallbackSourceUsed());
        assertEquals("HIT", retrieval.getOutcome());
        assertEquals(1, result.getSources().size());
    }

    @Test
    void guardrailShouldExposeKeywordRetrievalAndFallbackSource() {
        KnowledgeSearchService knowledgeSearchService = mock(KnowledgeSearchService.class);
        PublishAssistGuardrailServiceImpl guardrail = new PublishAssistGuardrailServiceImpl(
                knowledgeSearchService,
                new RagSourceMergeHelper(),
                new PublishAssistRetrievalTraceHelper()
        );
        KnowledgeSearchResponseVO emptyResponse = new KnowledgeSearchResponseVO();
        emptyResponse.setQuery("商品信息不足 能否售卖");
        emptyResponse.setTopK(5);
        emptyResponse.setTotal(0);
        emptyResponse.setHits(List.of());
        emptyResponse.setRetrievalMode("keyword-only");
        emptyResponse.setVectorHitCount(0);
        emptyResponse.setKeywordHitCount(0);
        emptyResponse.setDebugInfo("keywordOnly=true,esHits=0");
        when(knowledgeSearchService.search(anyString(), eq(5), any(KnowledgeSearchFilterDTO.class),
                eq(RetrievalMode.KEYWORD_ONLY))).thenReturn(emptyResponse);

        ProductPublishAssistRequestDTO request = new ProductPublishAssistRequestDTO();
        request.setTitle("这个东西能卖吗");
        request.setDescription("没写商品名称，也没想好怎么描述，帮我判断。");
        ProductPublishAssistVO result = guardrail.apply(request, new ArrayList<>()).orElseThrow();

        assertEquals(1, result.getRetrievals().size());
        PublishAssistRetrievalVO retrieval = result.getRetrievals().get(0);
        assertEquals("keyword-only", retrieval.getRetrievalMode());
        assertEquals("guardrail", retrieval.getStage());
        assertEquals("RULE", retrieval.getSourceType());
        assertEquals("FALLBACK_SOURCE", retrieval.getOutcome());
        assertTrue(retrieval.getFallbackSourceUsed());
        assertEquals(1, result.getSources().size());
    }

    @Test
    void optimizeAndPricingStepsShouldExposeTheirOwnRetrievalStages() {
        AiProductService aiProductService = mock(AiProductService.class);
        PricingSuggestionService pricingSuggestionService = mock(PricingSuggestionService.class);
        KnowledgeSearchService knowledgeSearchService = mock(KnowledgeSearchService.class);
        when(aiProductService.optimizeProductInfo(any())).thenReturn(new ProductOptimizeResponseVO());
        when(pricingSuggestionService.buildPriceSuggestion(any(ProductPublishAssistRequestDTO.class), any()))
                .thenReturn("请参考平台同类商品近期成交情况");
        when(knowledgeSearchService.search(anyString(), eq(5), any(KnowledgeSearchFilterDTO.class)))
                .thenReturn(hybridResponse());

        OptimizeStepImpl optimizeStep = new OptimizeStepImpl(
                aiProductService,
                knowledgeSearchService,
                new RagSourceMergeHelper(),
                new PublishAssistRetrievalTraceHelper()
        );
        PricingStepImpl pricingStep = new PricingStepImpl(
                pricingSuggestionService,
                new RagSourceMergeHelper(),
                knowledgeSearchService,
                new PublishAssistRetrievalTraceHelper()
        );
        PublishInputContext context = new PublishInputContext();
        context.setTitle("二手 RTX3060 显卡");
        context.setBrand("NVIDIA");
        context.setDescription("正常自用，无维修。");
        context.setConditionText("正常使用");
        context.setCategoryName("显卡");
        PublishAssistResult result = new PublishAssistResult();
        result.setPipelineRound(1);

        optimizeStep.apply(context, result);
        pricingStep.apply(context, result);

        assertEquals(List.of("optimize", "pricing"), result.getRetrievals().stream()
                .map(PublishAssistRetrievalVO::getStage)
                .toList());
        assertEquals(List.of("GUIDE", "MARKET"), result.getRetrievals().stream()
                .map(PublishAssistRetrievalVO::getSourceType)
                .toList());
    }

    private KnowledgeSearchResponseVO hybridResponse() {
        KnowledgeSearchHitVO hit = new KnowledgeSearchHitVO();
        hit.setDocId(19L);
        hit.setChunkId(90L);
        hit.setDocTitle("显卡发布风险规则");
        hit.setSnippet("显卡应如实披露维修史和温度情况。");
        hit.setSourceType(KnowledgeSourceType.RULE.name());
        hit.setScore(0.87D);

        KnowledgeSearchResponseVO response = new KnowledgeSearchResponseVO();
        response.setQuery("二手 RTX3060 显卡 正常自用 无维修 支持平台交易 违规规则 风险判断");
        response.setTopK(5);
        response.setTotal(1);
        response.setHits(List.of(hit));
        response.setRetrievalMode("hybrid");
        response.setVectorHitCount(3);
        response.setKeywordHitCount(2);
        response.setRewriteUsed(true);
        response.setRerankUsed(true);
        response.setRerankCandidateCount(5);
        response.setSelectedTopK(1);
        response.setRetrievalElapsedMs(128L);
        response.setDebugInfo("vectorHits=3,keywordHits=2,rrfK=60,esUsed=true,fallback=false");
        return response;
    }
}
