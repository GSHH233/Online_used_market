package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeChunk;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeChunkMapper;
import org.example.online_used_marketplace.modules.ai.service.KeywordSearchService;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeMetadata;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * ES/BM25 关键词检索实现，ES 异常时自动回退 MySQL 词面匹配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordSearchServiceImpl implements KeywordSearchService {

    private final ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    private final ThreadLocal<String> lastDebugInfo = new ThreadLocal<>();

    @Override
    public List<RagSourceVO> searchByKeyword(String question, int topK, KnowledgeSearchFilterDTO filter) {
        List<String> terms = buildKeywordTerms(question);
        if (terms.isEmpty()) {
            lastDebugInfo.set("esUsed=false,fallback=true,reason=emptyTerms");
            return Collections.emptyList();
        }

        try {
            ElasticsearchOperations esOps = elasticsearchOperationsProvider.getIfAvailable();
            if (esOps == null) {
                List<RagSourceVO> fallbackHits = searchFromMySqlMvp(terms, topK, filter);
                lastDebugInfo.set("esUsed=false,fallback=true,reason=noEsBean,fallbackHits=" + fallbackHits.size());
                return fallbackHits;
            }
            ensureEsIndex(esOps);
            List<RagSourceVO> esHits = searchFromEs(esOps, terms, topK, filter);
            lastDebugInfo.set("esUsed=true,fallback=false,esHits=" + esHits.size());
            return esHits;
        } catch (Exception ex) {
            log.warn("ES keyword search failed, fallback to mysql-mvp, reason={}", ex.getMessage());
            List<RagSourceVO> fallbackHits = searchFromMySqlMvp(terms, topK, filter);
            lastDebugInfo.set("esUsed=false,fallback=true,reason=" + ex.getClass().getSimpleName() + ",fallbackHits=" + fallbackHits.size());
            return fallbackHits;
        }
    }

    @Override
    public String getLastDebugInfo() {
        String debug = lastDebugInfo.get();
        return StringUtils.hasText(debug) ? debug : "esUsed=unknown";
    }

    private List<RagSourceVO> searchFromEs(ElasticsearchOperations esOps, List<String> terms, int topK, KnowledgeSearchFilterDTO filter) {
        Criteria criteria = null;
        for (String term : terms) {
            Criteria termCriteria = new Criteria("content").matches(term)
                    .or(new Criteria("docTitle").matches(term))
                    .or(new Criteria("sourceUri").matches(term));
            criteria = (criteria == null) ? termCriteria : criteria.or(termCriteria);
        }

        if (filter != null && filter.getSourceType() != null) {
            criteria = Objects.requireNonNull(criteria).and(new Criteria("sourceType").is(filter.getSourceType().name()));
        }
        if (filter != null && StringUtils.hasText(filter.getCategory())) {
            criteria = Objects.requireNonNull(criteria).and(new Criteria("category").is(filter.getCategory().trim()));
        }
        if (filter != null && StringUtils.hasText(filter.getRiskLevel())) {
            criteria = Objects.requireNonNull(criteria).and(new Criteria("riskLevel").is(filter.getRiskLevel().trim()));
        }

        CriteriaQuery query = new CriteriaQuery(criteria);
        query.setPageable(org.springframework.data.domain.PageRequest.of(0, Math.max(topK, 1)));

        SearchHits<KnowledgeChunkEsDoc> hits = esOps.search(query, KnowledgeChunkEsDoc.class);
        List<RagSourceVO> out = new ArrayList<>();
        for (SearchHit<KnowledgeChunkEsDoc> hit : hits) {
            KnowledgeChunkEsDoc doc = hit.getContent();
            if (doc == null) {
                continue;
            }
            RagSourceVO source = new RagSourceVO();
            source.setDocId(doc.getDocId());
            source.setChunkId(doc.getChunkId());
            source.setDocTitle(doc.getDocTitle());
            source.setSnippet(doc.getContent());
            source.setScore(Double.valueOf(hit.getScore()));
            source.setSourceType(doc.getSourceType());
            source.setSourceUri(doc.getSourceUri());
            source.setSourceTrustLevel(doc.getSourceTrustLevel());
            source.setDocVersion(doc.getDocVersion());
            source.setEffectiveFrom(doc.getEffectiveFrom());
            source.setEffectiveTo(doc.getEffectiveTo());
            source.setDocStatus(doc.getDocStatus());
            source.setDisabledReason(doc.getDisabledReason());
            source.setIndexVersion(doc.getIndexVersion());
            out.add(source);
        }
        return out;
    }

    private void ensureEsIndex(ElasticsearchOperations esOps) {
        IndexOperations indexOps = esOps.indexOps(KnowledgeChunkEsDoc.class);
        if (!indexOps.exists()) {
            boolean created = indexOps.create();
            log.info("[ES] create index ai_knowledge_chunk before keyword search: {}", created);
        }
        indexOps.putMapping(indexOps.createMapping(KnowledgeChunkEsDoc.class));
    }

    private List<RagSourceVO> searchFromMySqlMvp(List<String> terms, int topK, KnowledgeSearchFilterDTO filter) {
        LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.last("limit " + Math.max(topK * 5, 20));
        List<KnowledgeChunk> rows = knowledgeChunkMapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<RagSourceVO> scored = new ArrayList<>();
        for (KnowledgeChunk row : rows) {
            KnowledgeMetadata metadata = row.getMetadata();
            String docTitle = metadata == null ? "" : safe(metadata.getDocTitle());
            String sourceType = metadata == null ? "" : safe(metadata.getSourceType());
            String sourceUri = metadata == null ? "" : safe(metadata.getSourceUri());
            String sourceTrustLevel = metadata == null ? "" : safe(metadata.getSourceTrustLevel());
            String docVersion = metadata == null ? "" : safe(metadata.getDocVersion());
            String effectiveFrom = metadata == null ? "" : safe(metadata.getEffectiveFrom());
            String effectiveTo = metadata == null ? "" : safe(metadata.getEffectiveTo());
            String docStatus = metadata == null ? "" : safe(metadata.getDocStatus());
            String disabledReason = metadata == null ? "" : safe(metadata.getDisabledReason());
            String indexVersion = metadata == null ? "" : safe(metadata.getIndexVersion());
            String headings = metadata == null || metadata.getHeadingPath() == null
                    ? "" : String.join(" ", metadata.getHeadingPath());
            String corpus = (docTitle + " " + headings + " " + safe(row.getContent())
                    + " " + sourceUri + " " + sourceType).toLowerCase(Locale.ROOT);

            double matched = 0D;
            double total = 0D;
            for (String term : terms) {
                double weight = term.length() >= 4 ? 1.25D : 1D;
                total += weight;
                if (corpus.contains(term)) {
                    matched += weight;
                }
            }
            if (matched <= 0) {
                continue;
            }

            RagSourceVO vo = new RagSourceVO();
            vo.setDocId(row.getDocId());
            vo.setChunkId(row.getId());
            vo.setDocTitle(docTitle);
            vo.setSnippet(row.getContent());
            vo.setScore(matched / Math.max(total, 1D));
            vo.setSourceType(sourceType);
            vo.setSourceUri(sourceUri);
            vo.setSourceTrustLevel(sourceTrustLevel);
            vo.setDocVersion(docVersion);
            vo.setEffectiveFrom(effectiveFrom);
            vo.setEffectiveTo(effectiveTo);
            vo.setDocStatus(docStatus);
            vo.setDisabledReason(disabledReason);
            vo.setIndexVersion(indexVersion);
            scored.add(vo);
        }

        scored.sort(Comparator.comparing(RagSourceVO::getScore, Comparator.nullsLast(Comparator.reverseOrder())));
        return scored.stream().limit(topK).toList();
    }

    private List<String> buildKeywordTerms(String question) {
        if (!StringUtils.hasText(question)) {
            return Collections.emptyList();
        }
        String q = question.trim().toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();

        for (String part : q.split("\\s+|，|。|,|;|；|:|：|/|\\\\|\\(|\\)|\\[|\\]|\"|'")) {
            String token = normalizeTerm(part);
            if (StringUtils.hasText(token) && token.length() >= 2) {
                terms.add(token);
            }
        }
        String compact = q.replaceAll("\\s+", "");
        for (int i = 0; i < compact.length() - 1; i++) {
            String bi = compact.substring(i, i + 2);
            if (containsChineseOrDigit(bi)) {
                terms.add(bi);
            }
        }

        return new ArrayList<>(terms);
    }

    private String normalizeTerm(String term) {
        if (!StringUtils.hasText(term)) {
            return "";
        }
        return term.trim().toLowerCase(Locale.ROOT).replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
    }

    private boolean containsChineseOrDigit(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isDigit(ch) || Character.isLetter(ch) || isChinese(ch)) {
                return true;
            }
        }
        return false;
    }

    private boolean isChinese(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT;
    }

    private String safe(String text) {
        return StringUtils.hasText(text) ? text : "";
    }
}
