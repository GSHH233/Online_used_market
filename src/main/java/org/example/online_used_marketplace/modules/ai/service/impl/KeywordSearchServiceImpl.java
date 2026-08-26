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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    @Override
    public List<RagSourceVO> searchByKeyword(String question, int topK, KnowledgeSearchFilterDTO filter) {
        KnowledgeEvidencePlanner.Plan evidencePlan = KnowledgeEvidencePlanner.plan(question);
        List<String> terms = buildKeywordTerms(question);
        List<String> conceptTerms = evidencePlan.keywordExpansions();
        if (terms.isEmpty() && conceptTerms.isEmpty()) {
            lastDebugInfo.set("esUsed=false,fallback=true,reason=emptyTerms");
            return Collections.emptyList();
        }

        try {
            ElasticsearchOperations esOps = elasticsearchOperationsProvider.getIfAvailable();
            if (esOps == null) {
                List<RagSourceVO> fallbackHits = searchFromMySqlLanes(
                        terms, conceptTerms, evidencePlan, topK, filter);
                lastDebugInfo.set("esUsed=false,fallback=true,reason=noEsBean,fallbackHits=" + fallbackHits.size());
                return fallbackHits;
            }
            ensureEsIndex(esOps);
            List<RagSourceVO> lexicalHits = searchFromEs(esOps, terms, topK, filter);
            List<RagSourceVO> conceptHits = conceptTerms.isEmpty()
                    ? List.of()
                    : searchFromEs(esOps, conceptTerms, conceptLaneK(topK), filter);
            List<List<RagSourceVO>> evidenceHits = searchEvidenceLanesFromEs(
                    esOps, evidencePlan, topK, filter);
            List<RagSourceVO> esHits = fuseKeywordLanes(
                    lexicalHits, conceptHits, evidenceHits, topK);
            lastDebugInfo.set("esUsed=true,fallback=false,esHits=" + esHits.size()
                    + ",lexicalHits=" + lexicalHits.size()
                    + ",conceptHits=" + conceptHits.size()
                    + ",evidenceLanes=" + evidenceHits.size()
                    + ",evidenceHits=" + evidenceHits.stream().mapToInt(List::size).sum());
            return esHits;
        } catch (Exception ex) {
            indexReady.set(false);
            log.warn("ES keyword search failed, fallback to mysql-mvp, reason={}", ex.getMessage());
            List<RagSourceVO> fallbackHits = searchFromMySqlLanes(
                    terms, conceptTerms, evidencePlan, topK, filter);
            lastDebugInfo.set("esUsed=false,fallback=true,reason=" + ex.getClass().getSimpleName() + ",fallbackHits=" + fallbackHits.size());
            return fallbackHits;
        }
    }

    private List<RagSourceVO> searchFromMySqlLanes(List<String> terms,
                                                    List<String> conceptTerms,
                                                    KnowledgeEvidencePlanner.Plan evidencePlan,
                                                    int topK,
                                                    KnowledgeSearchFilterDTO filter) {
        List<RagSourceVO> lexicalHits = terms.isEmpty()
                ? List.of()
                : searchFromMySqlMvp(terms, topK, filter);
        List<RagSourceVO> conceptHits = conceptTerms.isEmpty()
                ? List.of()
                : searchFromMySqlMvp(conceptTerms, conceptLaneK(topK), filter);
        List<List<RagSourceVO>> evidenceHits = new ArrayList<>();
        for (KnowledgeEvidencePlanner.Requirement requirement : evidencePlan.requirements()) {
            List<String> evidenceTerms = evidenceLaneTerms(evidencePlan, requirement);
            if (!evidenceTerms.isEmpty()) {
                evidenceHits.add(searchFromMySqlMvp(
                        evidenceTerms, evidenceLaneK(topK), filter));
            }
        }
        return fuseKeywordLanes(lexicalHits, conceptHits, evidenceHits, topK);
    }

    private int conceptLaneK(int topK) {
        return Math.max(2, Math.min(8, Math.max(1, topK) / 3));
    }

    private int evidenceLaneK(int topK) {
        return Math.max(2, Math.min(8, Math.max(1, topK) / 4));
    }

    private List<List<RagSourceVO>> searchEvidenceLanesFromEs(
            ElasticsearchOperations esOps,
            KnowledgeEvidencePlanner.Plan plan,
            int topK,
            KnowledgeSearchFilterDTO filter) {
        List<List<RagSourceVO>> lanes = new ArrayList<>();
        Map<String, List<RagSourceVO>> cache = new LinkedHashMap<>();
        for (KnowledgeEvidencePlanner.Requirement requirement : plan.requirements()) {
            List<String> evidenceTerms = evidenceLaneTerms(plan, requirement);
            if (evidenceTerms.isEmpty()) {
                continue;
            }
            String key = String.join("\u0001", evidenceTerms);
            List<RagSourceVO> hits = cache.computeIfAbsent(key,
                    ignored -> searchFromEs(esOps, evidenceTerms, evidenceLaneK(topK), filter));
            lanes.add(hits);
        }
        return lanes;
    }

    private List<String> evidenceLaneTerms(KnowledgeEvidencePlanner.Plan plan,
                                           KnowledgeEvidencePlanner.Requirement requirement) {
        Set<String> concepts = new LinkedHashSet<>(requirement.searchConcepts());
        if (Set.of("REPAIR_HISTORY", "ACCESSORY_COMPLETENESS", "PROVENANCE")
                .contains(requirement.id())) {
            concepts.addAll(plan.productConcepts());
        }
        return KnowledgeQueryConcepts.keywordExpansions(concepts);
    }

    List<RagSourceVO> fuseKeywordLanes(List<RagSourceVO> lexicalHits,
                                       List<RagSourceVO> conceptHits,
                                       int topK) {
        return fuseKeywordLanes(lexicalHits, conceptHits, List.of(), topK);
    }

    List<RagSourceVO> fuseKeywordLanes(List<RagSourceVO> lexicalHits,
                                       List<RagSourceVO> conceptHits,
                                       List<List<RagSourceVO>> evidenceHits,
                                       int topK) {
        Map<String, RagSourceVO> sources = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        addLaneScores(sources, scores, lexicalHits, 1.0D);
        addLaneScores(sources, scores, conceptHits, 0.90D);
        if (evidenceHits != null) {
            for (List<RagSourceVO> evidenceLane : evidenceHits) {
                addLaneScores(sources, scores, evidenceLane, 1.10D);
            }
        }
        List<Map.Entry<String, Double>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();
        int limit = Math.max(1, topK);
        Set<String> selectedKeys = new LinkedHashSet<>();
        if (evidenceHits != null) {
            int maxLaneSize = evidenceHits.stream()
                    .filter(Objects::nonNull)
                    .mapToInt(List::size)
                    .max()
                    .orElse(0);
            for (int rank = 0; rank < maxLaneSize && selectedKeys.size() < limit; rank++) {
                for (List<RagSourceVO> evidenceLane : evidenceHits) {
                    if (selectedKeys.size() >= limit) {
                        break;
                    }
                    if (evidenceLane == null || rank >= evidenceLane.size()) {
                        continue;
                    }
                    String key = sourceKey(evidenceLane.get(rank));
                    if (sources.containsKey(key)) {
                        selectedKeys.add(key);
                    }
                }
            }
        }
        for (Map.Entry<String, Double> entry : ranked) {
            if (selectedKeys.size() >= limit) {
                break;
            }
            selectedKeys.add(entry.getKey());
        }
        return selectedKeys.stream()
                .sorted(Comparator.comparingDouble((String key) -> scores.getOrDefault(key, 0.0D)).reversed())
                .map(key -> {
                    RagSourceVO source = sources.get(key);
                    source.setScore(scores.getOrDefault(key, 0.0D));
                    return source;
                })
                .toList();
    }

    private void addLaneScores(Map<String, RagSourceVO> sources,
                               Map<String, Double> scores,
                               List<RagSourceVO> hits,
                               double weight) {
        if (hits == null) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            RagSourceVO source = hits.get(i);
            String key = sourceKey(source);
            sources.putIfAbsent(key, source);
            scores.merge(key, weight / (60.0D + i + 1), Double::sum);
        }
    }

    private String sourceKey(RagSourceVO source) {
        if (source == null) {
            return "";
        }
        return source.getChunkId() != null
                ? "chunk:" + source.getChunkId()
                : "doc:" + source.getDocId() + ":" + safe(source.getDocTitle());
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
            source.setLogicalKnowledgeId(doc.getLogicalKnowledgeId());
            out.add(source);
        }
        return out;
    }

    private void ensureEsIndex(ElasticsearchOperations esOps) {
        if (indexReady.get()) {
            return;
        }
        synchronized (indexReady) {
            if (indexReady.get()) {
                return;
            }
        IndexOperations indexOps = esOps.indexOps(KnowledgeChunkEsDoc.class);
        if (!indexOps.exists()) {
            boolean created = indexOps.create();
            log.info("[ES] create index ai_knowledge_chunk before keyword search: {}", created);
        }
        indexOps.putMapping(indexOps.createMapping(KnowledgeChunkEsDoc.class));
            indexReady.set(true);
        }
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
            String logicalKnowledgeId = metadata == null ? "" : safe(metadata.getLogicalKnowledgeId());
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
            vo.setLogicalKnowledgeId(logicalKnowledgeId);
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
