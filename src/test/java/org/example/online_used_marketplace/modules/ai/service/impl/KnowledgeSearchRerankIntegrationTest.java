package org.example.online_used_marketplace.modules.ai.service.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeDocMapper;
import org.example.online_used_marketplace.modules.ai.service.EmbeddingService;
import org.example.online_used_marketplace.modules.ai.service.KeywordSearchService;
import org.example.online_used_marketplace.modules.ai.service.QueryRewriteService;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.QueryRewritePlanVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSearchRerankIntegrationTest {

    @Test
    void hybridSearchFiltersRetiredRevisionAndReranksCandidatePool() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        VectorStore vectorStore = mock(VectorStore.class);
        KeywordSearchService keyword = mock(KeywordSearchService.class);
        KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        QueryRewriteService rewrite = mock(QueryRewriteService.class);
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getRetrieval().setMinResults(1);
        properties.getRetrieval().setRelativeScoreThreshold(0.70);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);
        KnowledgeRetrievalExecutor retrievalExecutor = new KnowledgeRetrievalExecutor(properties);

        RagSourceVO unrelated = source(1L, "显示器坏点规则", "显示器坏点位置", "idx-1");
        RagSourceVO relevant = source(2L, "相机镜头霉斑规则", "霉斑必须披露，不能写准新", "idx-2");
        RagSourceVO retiredRevision = source(3L, "相机历史规则", "霉斑旧版规则", "idx-3");
        when(embedding.embed(any())).thenReturn(List.of(new float[]{1F, 0F}));
        when(vectorStore.search(any(float[].class), anyInt(), anyMap()))
                .thenReturn(List.of(unrelated, relevant, retiredRevision));
        when(keyword.searchByKeyword(any(), anyInt(), any()))
                .thenReturn(List.of(relevant, retiredRevision));
        when(keyword.getLastDebugInfo()).thenReturn("esUsed=true,fallback=false");
        KnowledgeDoc retired = activeDoc(3L, "idx-3");
        retired.setStatus(KnowledgeDocStatus.RETIRED.name());
        when(docMapper.selectBatchIds(any()))
                .thenReturn(List.of(activeDoc(1L, "idx-1"), activeDoc(2L, "idx-2"), retired));
        QueryRewritePlanVO original = new QueryRewritePlanVO();
        original.setOriginalQuery("相机镜头有霉斑能写准新吗");
        original.setTriggerReason("request_disabled");
        when(rewrite.originalOnly(any(), any())).thenReturn(original);

        KnowledgeSearchServiceImpl service = new KnowledgeSearchServiceImpl(
                embedding, vectorStore, keyword, docMapper, rewrite, reranker,
                retrievalExecutor, properties, meterRegistry);
        KnowledgeSearchResponseVO response;
        try {
            response = service.searchHybrid("相机镜头有霉斑能写准新吗", 3, null, false);
        } finally {
            retrievalExecutor.close();
        }

        assertTrue(response.getRerankUsed());
        assertEquals(2L, response.getHits().get(0).getDocId());
        assertFalse(response.getHits().stream().anyMatch(hit -> Long.valueOf(3L).equals(hit.getDocId())));
        assertNotNull(response.getHits().get(0).getRetrievalScore());
        assertNotNull(response.getHits().get(0).getRerankScore());
        assertTrue(response.getQueryFacets().contains("@camera"));
        assertTrue(response.getFacetCoverage() > 0D);
        assertTrue(response.getEvidencePlan().contains("PRODUCT_CAMERA"));
        assertTrue(response.getEvidenceCoverage() > 0D);
        assertEquals(1L, meterRegistry.find("rag.retrieval.rerank").timer().count());
        assertEquals(1L, meterRegistry.find("rag.retrieval.facet.coverage").summary().count());
        assertEquals(1L, meterRegistry.find("rag.retrieval.evidence.coverage").summary().count());
        verify(docMapper, times(1)).selectBatchIds(any());
    }

    @Test
    void skipsModelRewriteWhenLowScoreBaselineAlreadyCoversDeterministicFacets() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        VectorStore vectorStore = mock(VectorStore.class);
        KeywordSearchService keyword = mock(KeywordSearchService.class);
        KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        QueryRewriteService rewrite = mock(QueryRewriteService.class);
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getRetrieval().setMinResults(1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeterministicKnowledgeReranker reranker = new DeterministicKnowledgeReranker(properties);
        KnowledgeRetrievalExecutor retrievalExecutor = new KnowledgeRetrievalExecutor(properties);

        RagSourceVO grounded = source(47L, "证据不足拒答规则",
                "信息不足时不能直接判断，应要求补充商品类型和凭证", "idx-47");
        grounded.setScore(0.10D);
        when(embedding.embed(any())).thenReturn(List.of(new float[]{1F, 0F}));
        when(vectorStore.search(any(float[].class), anyInt(), anyMap())).thenReturn(List.of(grounded));
        when(keyword.searchByKeyword(any(), anyInt(), any())).thenReturn(List.of());
        when(keyword.getLastDebugInfo()).thenReturn("esUsed=true,fallback=false");
        when(docMapper.selectBatchIds(any())).thenReturn(List.of(activeDoc(47L, "idx-47")));
        when(rewrite.shouldRewriteBeforeRetrieval(any())).thenReturn(true);
        when(rewrite.originalOnly(any(), any())).thenAnswer(invocation -> {
            QueryRewritePlanVO plan = new QueryRewritePlanVO();
            plan.setOriginalQuery(invocation.getArgument(0));
            plan.setTriggerReason(invocation.getArgument(1));
            return plan;
        });

        KnowledgeSearchServiceImpl service = new KnowledgeSearchServiceImpl(
                embedding, vectorStore, keyword, docMapper, rewrite, reranker,
                retrievalExecutor, properties, meterRegistry);
        KnowledgeSearchResponseVO response;
        try {
            response = service.searchHybrid(
                    "只知道它是数码产品，能直接判断允许出售吗？", 5, null, true);
        } finally {
            retrievalExecutor.close();
        }

        assertFalse(response.getRewriteUsed());
        assertEquals("deterministic_coverage_fast_path", response.getRewriteTrigger());
        verify(rewrite, never()).rewrite(any(), any());
    }

    private RagSourceVO source(Long docId, String title, String snippet, String indexVersion) {
        RagSourceVO source = new RagSourceVO();
        source.setDocId(docId);
        source.setChunkId(docId * 10);
        source.setDocTitle(title);
        source.setSnippet(snippet);
        source.setScore(0.8D);
        source.setIndexVersion(indexVersion);
        source.setSourceTrustLevel("MEDIUM");
        return source;
    }

    private KnowledgeDoc activeDoc(Long id, String indexVersion) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(id);
        doc.setStatus(KnowledgeDocStatus.ACTIVE.name());
        doc.setLifecycleManaged(true);
        doc.setActiveIndexVersion(indexVersion);
        doc.setLogicalKnowledgeId("rule:" + id);
        return doc;
    }
}
