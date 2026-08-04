package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationRequest;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationResult;
import org.example.online_used_marketplace.modules.ai.vo.QueryRewritePlanVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryRewriteTriggerTest {

    @Test
    void keepsSimpleQuestionOnFastPathAndTriggersComplexQuestion() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                null, new ObjectMapper(), properties, new SimpleMeterRegistry());
        try {
            assertFalse(service.shouldRewriteBeforeRetrieval("处方药能发布吗"));
            assertTrue(service.shouldRewriteBeforeRetrieval(
                    "无人机炸过机但是已经维修，同时电池循环次数没有写，这两项分别有什么发布风险"));
        } finally {
            service.close();
        }
    }

    @Test
    void disabledRewriteFallsBackWithoutCallingModel() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getQueryRewrite().setEnabled(false);
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                null, new ObjectMapper(), properties, new SimpleMeterRegistry());
        try {
            QueryRewritePlanVO plan = service.rewrite("测试问题", "test");
            assertFalse(plan.isRewriteUsed());
            assertTrue("disabled".equals(plan.getTriggerReason()));
        } finally {
            service.close();
        }
    }

    @Test
    void acceptsColloquialTermsThroughStructuredConstraintsAndKeepsSubQueries() {
        ChatGenerationService generationService = mock(ChatGenerationService.class);
        when(generationService.generate(any(TokenGenerationRequest.class))).thenReturn(TokenGenerationResult.builder()
                .content("""
                        {"originalQuery":"这个没票还写全新能发吗，同时买家追问购买凭证时应该怎么解释",
                         "mainQuery":"无发票商品宣称全新时的发布风险与购买凭证说明",
                         "subQueries":["无发票商品的发布风险","买家追问购买凭证时如何说明"],
                         "synonymQueries":["缺少发票的商品发布要求"],
                         "mustKeepTerms":["没票","全新","购买凭证"],
                         "negations":["没"],"rewriteReason":"口语规范化并拆分意图"}
                        """)
                .build());
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getQueryRewrite().setTimeoutMs(2_000);
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                generationService, new ObjectMapper(), properties, new SimpleMeterRegistry());
        try {
            QueryRewritePlanVO plan = service.rewrite(
                    "这个没票还写全新能发吗，同时买家追问购买凭证时应该怎么解释", "test");
            assertTrue(plan.isRewriteUsed());
            assertFalse(plan.isFallback());
            assertEquals(2, plan.getSubQueries().size());
            assertTrue(plan.getMustKeepTerms().containsAll(List.of("没票", "全新", "购买凭证")));
            assertTrue(plan.getNegations().contains("没"));
        } finally {
            service.close();
        }
    }

    @Test
    void fallsBackWhenMainRewriteIntroducesNewNumericLiteral() {
        ChatGenerationService generationService = mock(ChatGenerationService.class);
        when(generationService.generate(any(TokenGenerationRequest.class))).thenReturn(TokenGenerationResult.builder()
                .content("""
                        {"originalQuery":"没有市场证据时能否给折扣",
                         "mainQuery":"没有市场证据时给出15%折扣",
                         "subQueries":[],"synonymQueries":[],
                         "mustKeepTerms":["没有"],"negations":["没有"],
                         "rewriteReason":"补全问题"}
                        """)
                .build());
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getQueryRewrite().setTimeoutMs(2_000);
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                generationService, new ObjectMapper(), properties, new SimpleMeterRegistry());
        try {
            QueryRewritePlanVO plan = service.rewrite("没有市场证据时能否给折扣", "test");
            assertFalse(plan.isRewriteUsed());
            assertTrue(plan.isFallback());
            assertTrue(plan.getFallbackReason().contains("introduced protected literal"));
        } finally {
            service.close();
        }
    }
}
