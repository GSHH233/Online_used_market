package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus;
import org.example.online_used_marketplace.modules.ai.enums.RetrievalMode;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeDocMapper;
import org.example.online_used_marketplace.modules.ai.service.EmbeddingService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.KeywordSearchService;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchHitVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索服务实现：支持向量、关键词、混合三种检索模式。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeSearchServiceImpl implements KnowledgeSearchService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int HYBRID_CANDIDATE_K = 20;
    private static final int RRF_K = 60;

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final KeywordSearchService keywordSearchService;
    private final KnowledgeDocMapper knowledgeDocMapper;

    @Override
    public KnowledgeSearchResponseVO search(String question, Integer topK, KnowledgeSearchFilterDTO filter) {
        return search(question, topK, filter, RetrievalMode.VECTOR_ONLY);
    }

    @Override
    public KnowledgeSearchResponseVO search(String question, Integer topK, KnowledgeSearchFilterDTO filter, RetrievalMode mode) {
        String normalizedQuestion = normalizeQuestion(question);
        int normalizedTopK = normalizeTopK(topK);
        RetrievalMode normalizedMode = mode == null ? RetrievalMode.HYBRID : mode;

        if (normalizedMode == RetrievalMode.KEYWORD_ONLY) {
            return searchKeywordOnly(normalizedQuestion, normalizedTopK, filter);
        }
        if (normalizedMode == RetrievalMode.HYBRID) {
            return searchHybrid(normalizedQuestion, normalizedTopK, filter);
        }
        return searchVectorOnly(normalizedQuestion, normalizedTopK, filter);
    }

    @Override
    public KnowledgeSearchResponseVO searchHybrid(String question, Integer topK, KnowledgeSearchFilterDTO filter) {
        String normalizedQuestion = normalizeQuestion(question);
        int normalizedTopK = normalizeTopK(topK);
        int candidateK = Math.max(normalizedTopK, HYBRID_CANDIDATE_K);

        List<RagSourceVO> vectorHits = applyGovernance(doVectorSearch(normalizedQuestion, candidateK, filter));
        List<RagSourceVO> keywordHits = applyGovernance(keywordSearchService.searchByKeyword(normalizedQuestion, candidateK, filter));
        String keywordDebug = keywordSearchService.getLastDebugInfo();
        List<RagSourceVO> fused = rrfFuse(vectorHits, keywordHits, normalizedTopK);

        KnowledgeSearchResponseVO vo = buildResponse(normalizedQuestion, normalizedTopK, fused, RetrievalMode.HYBRID);
        vo.setVectorHitCount(vectorHits.size());
        vo.setKeywordHitCount(keywordHits.size());
        vo.setDebugInfo("vectorHits=" + vectorHits.size()
                + ",keywordHits=" + keywordHits.size()
                + ",rrfK=" + RRF_K
                + ",candidateK=" + candidateK
                + "," + keywordDebug);
        return vo;
    }

    private KnowledgeSearchResponseVO searchVectorOnly(String normalizedQuestion, int normalizedTopK, KnowledgeSearchFilterDTO filter) {
        List<RagSourceVO> vectorSources = applyGovernance(doVectorSearch(normalizedQuestion, normalizedTopK, filter));
        KnowledgeSearchResponseVO vo = buildResponse(normalizedQuestion, normalizedTopK, vectorSources, RetrievalMode.VECTOR_ONLY);
        vo.setVectorHitCount(vectorSources.size());
        vo.setKeywordHitCount(0);
        vo.setDebugInfo("vectorOnly=true");
        return vo;
    }

    private KnowledgeSearchResponseVO searchKeywordOnly(String normalizedQuestion, int normalizedTopK, KnowledgeSearchFilterDTO filter) {
        List<RagSourceVO> keywordSources = applyGovernance(keywordSearchService.searchByKeyword(normalizedQuestion, normalizedTopK, filter));
        String keywordDebug = keywordSearchService.getLastDebugInfo();
        KnowledgeSearchResponseVO vo = buildResponse(normalizedQuestion, normalizedTopK, keywordSources, RetrievalMode.KEYWORD_ONLY);
        vo.setVectorHitCount(0);
        vo.setKeywordHitCount(keywordSources.size());
        vo.setDebugInfo("keywordOnly=true," + keywordDebug);
        return vo;
    }

    private List<RagSourceVO> doVectorSearch(String normalizedQuestion, int topK, KnowledgeSearchFilterDTO filter) {
        List<float[]> vectors = embeddingService.embed(List.of(normalizedQuestion));
        if (vectors == null || vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length == 0) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "向量生成失败");
        }
        Map<String, Object> filters = (filter == null) ? Collections.emptyMap() : toFilterMap(filter);
        List<RagSourceVO> sources = vectorStore.search(vectors.get(0), topK, filters);
        return sources == null ? Collections.emptyList() : sources;
    }

    private KnowledgeSearchResponseVO buildResponse(String query, int topK, List<RagSourceVO> sources, RetrievalMode mode) {
        List<KnowledgeSearchHitVO> hits = toHits(sources);
        KnowledgeSearchResponseVO vo = new KnowledgeSearchResponseVO();
        vo.setTopK(topK);
        vo.setQuery(query);
        vo.setTotal(hits.size());
        vo.setHits(hits);
        vo.setRetrievalMode(mode.getCode());
        return vo;
    }

    private List<RagSourceVO> rrfFuse(List<RagSourceVO> vectorHits, List<RagSourceVO> keywordHits, int topK) {
        Map<String, RagSourceVO> sourceByKey = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        addRrfScores(sourceByKey, rrfScores, vectorHits);
        addRrfScores(sourceByKey, rrfScores, keywordHits);

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(rrfScores.entrySet());
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<RagSourceVO> fused = new ArrayList<>();
        for (Map.Entry<String, Double> e : ranked) {
            RagSourceVO source = sourceByKey.get(e.getKey());
            if (source == null) {
                continue;
            }
            RagSourceVO out = copySource(source);
            out.setScore(e.getValue());
            fused.add(out);
            if (fused.size() >= topK) {
                break;
            }
        }
        return fused;
    }

    private void addRrfScores(Map<String, RagSourceVO> sourceByKey, Map<String, Double> rrfScores, List<RagSourceVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            RagSourceVO src = list.get(i);
            String key = dedupKey(src);
            if (!StringUtils.hasText(key)) {
                continue;
            }
            sourceByKey.putIfAbsent(key, src);
            double inc = 1.0D / (RRF_K + i + 1);
            rrfScores.merge(key, inc, Double::sum);
        }
    }

    private String dedupKey(RagSourceVO src) {
        if (src == null) {
            return "";
        }
        if (src.getDocId() != null && src.getChunkId() != null) {
            return src.getDocId() + "#" + src.getChunkId();
        }
        return StringUtils.hasText(src.getSnippet()) ? src.getSnippet().trim() : "";
    }

    private List<KnowledgeSearchHitVO> toHits(List<RagSourceVO> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeSearchHitVO> hits = new ArrayList<>();
        for (RagSourceVO source : sources) {
            KnowledgeSearchHitVO hit = new KnowledgeSearchHitVO();
            hit.setDocId(source.getDocId());
            hit.setDocTitle(source.getDocTitle());
            hit.setChunkId(source.getChunkId());
            hit.setSnippet(source.getSnippet());
            hit.setScore(source.getScore());
            hit.setSourceType(source.getSourceType());
            hit.setSourceUri(source.getSourceUri());
            hit.setSourceTrustLevel(source.getSourceTrustLevel());
            hit.setDocVersion(source.getDocVersion());
            hit.setEffectiveFrom(source.getEffectiveFrom());
            hit.setEffectiveTo(source.getEffectiveTo());
            hit.setDocStatus(source.getDocStatus());
            hit.setDisabledReason(source.getDisabledReason());
            hits.add(hit);
        }
        return hits;
    }

    private RagSourceVO copySource(RagSourceVO source) {
        RagSourceVO out = new RagSourceVO();
        out.setDocId(source.getDocId());
        out.setDocTitle(source.getDocTitle());
        out.setChunkId(source.getChunkId());
        out.setSnippet(source.getSnippet());
        out.setScore(source.getScore());
        out.setSourceType(source.getSourceType());
        out.setSourceUri(source.getSourceUri());
        out.setSourceTrustLevel(source.getSourceTrustLevel());
        out.setDocVersion(source.getDocVersion());
        out.setEffectiveFrom(source.getEffectiveFrom());
        out.setEffectiveTo(source.getEffectiveTo());
        out.setDocStatus(source.getDocStatus());
        out.setDisabledReason(source.getDisabledReason());
        return out;
    }

    private List<RagSourceVO> applyGovernance(List<RagSourceVO> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, KnowledgeDoc> docCache = new HashMap<>();
        List<RagSourceVO> out = new ArrayList<>();
        for (RagSourceVO source : sources) {
            if (source == null) {
                continue;
            }
            KnowledgeDoc doc = loadDoc(source.getDocId(), docCache);
            if (doc == null) {
                continue;
            }
            if (!isSearchable(doc, now)) {
                continue;
            }
            enrichGovernance(source, doc);
            out.add(source);
        }
        return out;
    }

    private KnowledgeDoc loadDoc(Long docId, Map<Long, KnowledgeDoc> cache) {
        if (docId == null) {
            return null;
        }
        if (!cache.containsKey(docId)) {
            cache.put(docId, knowledgeDocMapper.selectById(docId));
        }
        return cache.get(docId);
    }

    private boolean isSearchable(KnowledgeDoc doc, LocalDateTime now) {
        if (doc == null) {
            return false;
        }
        String status = doc.getStatus();
        if (KnowledgeDocStatus.DISABLED.name().equalsIgnoreCase(status)) {
            return false;
        }
        if (doc.getEffectiveFrom() != null && doc.getEffectiveFrom().isAfter(now)) {
            return false;
        }
        return doc.getEffectiveTo() == null || !doc.getEffectiveTo().isBefore(now);
    }

    private void enrichGovernance(RagSourceVO source, KnowledgeDoc doc) {
        source.setSourceTrustLevel(firstText(doc.getSourceTrustLevel(), source.getSourceTrustLevel()));
        source.setDocVersion(firstText(doc.getDocVersion(), source.getDocVersion()));
        source.setEffectiveFrom(doc.getEffectiveFrom() == null ? source.getEffectiveFrom() : doc.getEffectiveFrom().toString());
        source.setEffectiveTo(doc.getEffectiveTo() == null ? source.getEffectiveTo() : doc.getEffectiveTo().toString());
        source.setDocStatus(firstText(doc.getStatus(), source.getDocStatus()));
        source.setDisabledReason(firstText(doc.getDisabledReason(), source.getDisabledReason()));
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : fallback;
    }

    private Map<String, Object> toFilterMap(KnowledgeSearchFilterDTO filter) {
        Map<String, Object> filters = new HashMap<>();
        if (filter.getSourceType() != null) {
            filters.put("sourceType", filter.getSourceType().name());
        }
        if (StringUtils.hasText(filter.getCategory())) {
            filters.put("category", filter.getCategory().trim());
        }
        if (StringUtils.hasText(filter.getRiskLevel())) {
            filters.put("riskLevel", filter.getRiskLevel().trim());
        }
        if (filter.getExt() != null && !filter.getExt().isEmpty()) {
            filters.putAll(filter.getExt());
        }
        return filters;
    }

    private String normalizeQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "问题不能为空");
        }
        return question.trim();
    }

    private int normalizeTopK(Integer topK) {
        return topK == null || topK <= 0 ? DEFAULT_TOP_K : topK;
    }
}
