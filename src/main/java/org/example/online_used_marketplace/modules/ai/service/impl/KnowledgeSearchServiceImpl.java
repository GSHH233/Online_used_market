package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus;
import org.example.online_used_marketplace.modules.ai.enums.RetrievalMode;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeDocMapper;
import org.example.online_used_marketplace.modules.ai.service.EmbeddingService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeReranker;
import org.example.online_used_marketplace.modules.ai.service.KeywordSearchService;
import org.example.online_used_marketplace.modules.ai.service.QueryRewriteService;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchHitVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeRerankResult;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.example.online_used_marketplace.modules.ai.vo.QueryRewritePlanVO;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 知识检索服务实现：支持向量、关键词、混合三种检索模式。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeSearchServiceImpl implements KnowledgeSearchService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int HYBRID_CANDIDATE_K = 20;
    private static final int RRF_K = 60;

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final KeywordSearchService keywordSearchService;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final QueryRewriteService queryRewriteService;
    private final KnowledgeReranker knowledgeReranker;
    private final KnowledgeRetrievalExecutor retrievalExecutor;
    private final KnowledgeIngestionProperties ingestionProperties;
    private final MeterRegistry meterRegistry;

    @Override
    public KnowledgeSearchResponseVO search(String question, Integer topK, KnowledgeSearchFilterDTO filter) {
        return search(question, topK, filter, RetrievalMode.HYBRID);
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
        return searchHybrid(question, topK, filter, true);
    }

    @Override
    public KnowledgeSearchResponseVO searchHybrid(String question,
                                                  Integer topK,
                                                  KnowledgeSearchFilterDTO filter,
                                                  boolean rewriteEnabled) {
        long retrievalStartedNanos = System.nanoTime();
        String normalizedQuestion = normalizeQuestion(question);
        int normalizedTopK = normalizeTopK(topK);
        KnowledgeEvidencePlanner.Plan retrievalEvidencePlan = KnowledgeEvidencePlanner.plan(normalizedQuestion);
        int baseCandidateK = Math.max(normalizedTopK,
                Math.max(HYBRID_CANDIDATE_K, ingestionProperties.getRetrieval().getCandidateK()));
        int evidenceCandidateBudget = Math.max(0,
                ingestionProperties.getRetrieval().getEvidenceCandidatesPerRequirement())
                * retrievalEvidencePlan.requirements().size();
        int maxCandidateK = Math.max(baseCandidateK,
                ingestionProperties.getRetrieval().getMaxCandidateK());
        int candidateK = Math.min(maxCandidateK, baseCandidateK + evidenceCandidateBudget);
        Map<Long, KnowledgeDoc> governanceDocCache = new HashMap<>();

        long baselineStartedNanos = System.nanoTime();
        CompletableFuture<ChannelHits> vectorFuture = retrievalExecutor.submit(
                () -> vectorChannel(normalizedQuestion, candidateK, filter));
        CompletableFuture<ChannelHits> keywordFuture = retrievalExecutor.submit(
                () -> keywordChannel(normalizedQuestion, candidateK, filter));
        ChannelHits vectorBaseline = awaitChannel(vectorFuture, "vector_baseline");
        ChannelHits keywordBaseline = awaitChannel(keywordFuture, "keyword_baseline");
        List<RagSourceVO> vectorHits = applyGovernance(vectorBaseline.sources(), governanceDocCache);
        List<RagSourceVO> keywordHits = applyGovernance(keywordBaseline.sources(), governanceDocCache);
        List<List<RagSourceVO>> candidatePools = new ArrayList<>();
        candidatePools.add(keywordHits);
        candidatePools.add(vectorHits);
        String keywordDebug = keywordBaseline.debugInfo();
        long baselineElapsedMs = elapsedMs(baselineStartedNanos);
        List<RagSourceVO> baseline = rrfFuse(vectorHits, keywordHits, candidateK);
        boolean deterministicCoverageFastPath = hasDeterministicFacetCoverage(
                normalizedQuestion, baseline);

        boolean preRewrite = rewriteEnabled
                && queryRewriteService.shouldRewriteBeforeRetrieval(normalizedQuestion);
        QueryRewritePlanVO rewritePlan;
        if (preRewrite && !deterministicCoverageFastPath) {
            rewritePlan = queryRewriteService.rewrite(normalizedQuestion, "complex_or_contextual_query");
        } else if (rewriteEnabled && baseline.isEmpty()) {
            rewritePlan = queryRewriteService.rewrite(normalizedQuestion, "initial_no_hit");
        } else if (rewriteEnabled && isLowConfidence(vectorHits, keywordHits)
                && !deterministicCoverageFastPath) {
            rewritePlan = queryRewriteService.rewrite(normalizedQuestion, "initial_low_confidence");
        } else {
            rewritePlan = queryRewriteService.originalOnly(
                    normalizedQuestion,
                    !rewriteEnabled ? "request_disabled"
                            : deterministicCoverageFastPath ? "deterministic_coverage_fast_path" : "fast_path");
        }

        List<RagSourceVO> fused = baseline;
        int totalVectorHits = vectorHits.size();
        int totalKeywordHits = keywordHits.size();
        long branchRetrievalElapsedMs = 0L;
        if (rewritePlan.isRewriteUsed() && !ingestionProperties.getQueryRewrite().isShadowMode()) {
            long branchStartedNanos = System.nanoTime();
            List<WeightedRanking> rankings = new ArrayList<>();
            rankings.add(new WeightedRanking(vectorHits, 1.0));
            rankings.add(new WeightedRanking(keywordHits, 1.0));

            List<QueryRewritePlanVO.WeightedQuery> branches = rewritePlan.weightedQueries().stream()
                    .filter(branch -> !"ORIGINAL".equals(branch.type()))
                    .toList();
            int totalBudget = Math.max(candidateK,
                    ingestionProperties.getQueryRewrite().getTotalCandidateBudget());
            int branchChannels = branches.stream()
                    .mapToInt(branch -> branch.keywordEnabled() ? 2 : 1)
                    .sum();
            int baselineCandidates = vectorHits.size() + keywordHits.size();
            int remainingBudget = Math.max(1, totalBudget - baselineCandidates);
            int perBranchK = Math.max(1, remainingBudget / Math.max(1, branchChannels));
            List<BranchFuture> branchFutures = new ArrayList<>();
            for (QueryRewritePlanVO.WeightedQuery branch : branches) {
                branchFutures.add(new BranchFuture(
                        branch,
                        retrievalExecutor.submit(() -> vectorChannel(branch.query(), perBranchK, filter)),
                        branch.keywordEnabled()
                                ? retrievalExecutor.submit(() -> keywordChannel(branch.query(), perBranchK, filter))
                                : null));
            }
            for (BranchFuture branchFuture : branchFutures) {
                QueryRewritePlanVO.WeightedQuery branch = branchFuture.branch();
                List<RagSourceVO> branchVector = applyGovernance(
                        awaitChannel(branchFuture.vectorFuture(), "vector_" + branch.type()).sources(),
                        governanceDocCache);
                rankings.add(new WeightedRanking(branchVector, branch.weight()));
                candidatePools.add(branchVector);
                totalVectorHits += branchVector.size();
                if (branch.keywordEnabled()) {
                    List<RagSourceVO> branchKeyword = applyGovernance(
                            awaitChannel(branchFuture.keywordFuture(), "keyword_" + branch.type()).sources(),
                            governanceDocCache);
                    rankings.add(new WeightedRanking(branchKeyword, branch.weight()));
                    candidatePools.add(branchKeyword);
                    totalKeywordHits += branchKeyword.size();
                }
            }
            fused = weightedRrfFuse(rankings, candidateK);
            branchRetrievalElapsedMs = elapsedMs(branchStartedNanos);
            if (baseline.isEmpty() && !fused.isEmpty()) {
                meterRegistry.counter("rag_retrieval_no_hit_recovery_total").increment();
            }
        }

        fused = preserveEvidenceCandidates(
                fused, candidatePools, retrievalEvidencePlan, maxCandidateK);

        long rerankStartedNanos = System.nanoTime();
        KnowledgeRerankResult rerankResult;
        long rerankElapsedMs;
        try {
            rerankResult = knowledgeReranker.rerank(normalizedQuestion, fused, normalizedTopK);
        } finally {
            long rerankNanos = System.nanoTime() - rerankStartedNanos;
            meterRegistry.timer("rag.retrieval.rerank").record(rerankNanos, TimeUnit.NANOSECONDS);
            rerankElapsedMs = TimeUnit.NANOSECONDS.toMillis(rerankNanos);
        }
        meterRegistry.summary("rag.retrieval.rerank.candidates")
                .record(rerankResult.candidateCount());
        meterRegistry.summary("rag.retrieval.selected.topk")
                .record(rerankResult.sources().size());
        meterRegistry.summary("rag.retrieval.facet.coverage")
                .record(rerankResult.facetCoverage());
        meterRegistry.summary("rag.retrieval.evidence.coverage")
                .record(rerankResult.evidenceCoverage());
        fused = rerankResult.sources();
        KnowledgeSearchResponseVO vo = buildResponse(normalizedQuestion, normalizedTopK, fused, RetrievalMode.HYBRID);
        vo.setVectorHitCount(totalVectorHits);
        vo.setKeywordHitCount(totalKeywordHits);
        vo.setRewriteUsed(rewritePlan.isRewriteUsed()
                && !ingestionProperties.getQueryRewrite().isShadowMode());
        vo.setRewriteTrigger(rewritePlan.getTriggerReason());
        vo.setRewritePlan(rewritePlan);
        vo.setRewriteElapsedMs(rewritePlan.getElapsedMs());
        vo.setRerankUsed(rerankResult.rerankUsed());
        vo.setRerankCandidateCount(rerankResult.candidateCount());
        vo.setSelectedTopK(fused.size());
        vo.setDynamicTopKApplied(rerankResult.dynamicTopKApplied());
        vo.setQueryFacets(rerankResult.queryFacets());
        vo.setCoveredFacets(rerankResult.coveredFacets());
        vo.setFacetCoverage(rerankResult.facetCoverage());
        vo.setEvidencePlan(rerankResult.evidencePlan());
        vo.setCoveredEvidence(rerankResult.coveredEvidence());
        vo.setEvidenceCoverage(rerankResult.evidenceCoverage());
        vo.setBaselineElapsedMs(baselineElapsedMs);
        vo.setBranchRetrievalElapsedMs(branchRetrievalElapsedMs);
        vo.setRerankElapsedMs(rerankElapsedMs);
        vo.setRetrievalElapsedMs(elapsedMs(retrievalStartedNanos));
        vo.setDebugInfo("vectorHits=" + totalVectorHits
                + ",keywordHits=" + totalKeywordHits
                + ",rrfK=" + RRF_K
                + ",candidateK=" + candidateK
                + ",rewriteUsed=" + vo.getRewriteUsed()
                + ",rewriteShadow=" + ingestionProperties.getQueryRewrite().isShadowMode()
                + ",rewriteTrigger=" + rewritePlan.getTriggerReason()
                + ",rerankUsed=" + rerankResult.rerankUsed()
                + ",rerankCandidates=" + rerankResult.candidateCount()
                + ",selectedTopK=" + fused.size()
                + ",dynamicTopK=" + rerankResult.dynamicTopKApplied()
                + ",queryFacets=" + rerankResult.queryFacets()
                + ",coveredFacets=" + rerankResult.coveredFacets()
                + ",facetCoverage=" + String.format(java.util.Locale.ROOT, "%.3f", rerankResult.facetCoverage())
                + ",evidencePlan=" + rerankResult.evidencePlan()
                + ",coveredEvidence=" + rerankResult.coveredEvidence()
                + ",evidenceCoverage=" + String.format(java.util.Locale.ROOT, "%.3f", rerankResult.evidenceCoverage())
                + ",retrievalElapsedMs=" + vo.getRetrievalElapsedMs()
                + ",baselineElapsedMs=" + baselineElapsedMs
                + ",branchElapsedMs=" + branchRetrievalElapsedMs
                + ",rerankElapsedMs=" + rerankElapsedMs
                + "," + keywordDebug);
        return vo;
    }

    private ChannelHits vectorChannel(String query, int topK, KnowledgeSearchFilterDTO filter) {
        long startedNanos = System.nanoTime();
        return new ChannelHits(doVectorSearch(query, topK, filter), "", elapsedMs(startedNanos));
    }

    private ChannelHits keywordChannel(String query, int topK, KnowledgeSearchFilterDTO filter) {
        long startedNanos = System.nanoTime();
        List<RagSourceVO> sources = keywordSearchService.searchByKeyword(query, topK, filter);
        return new ChannelHits(sources, keywordSearchService.getLastDebugInfo(), elapsedMs(startedNanos));
    }

    private ChannelHits awaitChannel(CompletableFuture<ChannelHits> future, String channel) {
        if (future == null) {
            return ChannelHits.empty("disabled");
        }
        try {
            return future.get(
                    Math.max(100L, ingestionProperties.getRetrieval().getParallelTimeoutMs()),
                    TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            future.cancel(true);
            log.warn("knowledge retrieval channel failed, channel={}, reason={}", channel, ex.getMessage());
            meterRegistry.counter("rag_retrieval_channel_failure_total", "channel", channel).increment();
            return ChannelHits.empty(ex.getClass().getSimpleName());
        }
    }

    private long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private boolean isLowConfidence(List<RagSourceVO> vectorHits, List<RagSourceVO> keywordHits) {
        if (keywordHits != null && !keywordHits.isEmpty()) {
            return false;
        }
        double bestVectorScore = vectorHits == null ? Double.NEGATIVE_INFINITY : vectorHits.stream()
                .map(RagSourceVO::getScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(Double.NEGATIVE_INFINITY);
        return bestVectorScore < ingestionProperties.getQueryRewrite().getLowConfidenceThreshold();
    }

    private boolean hasDeterministicFacetCoverage(String query, List<RagSourceVO> baseline) {
        if (baseline == null || baseline.isEmpty()) {
            return false;
        }
        KnowledgeEvidencePlanner.Plan evidencePlan = KnowledgeEvidencePlanner.plan(query);
        Set<String> queryFacets = KnowledgeQueryConcepts.coverageTargets(evidencePlan.normalizedConcepts());
        if (queryFacets.isEmpty()) {
            return false;
        }
        Set<String> coveredFacets = new LinkedHashSet<>();
        Set<String> coveredEvidence = new LinkedHashSet<>();
        for (RagSourceVO source : baseline) {
            if (source == null) {
                continue;
            }
            String corpus = String.valueOf(source.getDocTitle()) + " " + String.valueOf(source.getSnippet());
            Set<String> candidateConcepts = KnowledgeQueryConcepts.detect(corpus);
            for (String candidateConcept : candidateConcepts) {
                if (queryFacets.contains(candidateConcept)) {
                    coveredFacets.add(candidateConcept);
                }
            }
            for (KnowledgeEvidencePlanner.Requirement requirement : evidencePlan.requirements()) {
                if (requirement.matches(candidateConcepts)) {
                    coveredEvidence.add(requirement.id());
                }
            }
        }
        boolean facetCovered = KnowledgeQueryConcepts.coverageRatio(queryFacets, coveredFacets)
                >= ingestionProperties.getRetrieval().getMinFacetCoverage();
        if (!ingestionProperties.getRetrieval().isEvidencePlanEnabled()
                || evidencePlan.requirements().isEmpty()) {
            return facetCovered;
        }
        double totalWeight = evidencePlan.requirements().stream()
                .mapToDouble(KnowledgeEvidencePlanner.Requirement::weight)
                .sum();
        double coveredWeight = evidencePlan.requirements().stream()
                .filter(requirement -> coveredEvidence.contains(requirement.id()))
                .mapToDouble(KnowledgeEvidencePlanner.Requirement::weight)
                .sum();
        double evidenceCoverage = totalWeight <= 0.0D ? 1.0D : coveredWeight / totalWeight;
        return facetCovered
                && evidenceCoverage >= ingestionProperties.getRetrieval().getMinEvidenceCoverage();
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
        addRrfScores(sourceByKey, rrfScores, list, 1.0);
    }

    private void addRrfScores(Map<String, RagSourceVO> sourceByKey,
                              Map<String, Double> rrfScores,
                              List<RagSourceVO> list,
                              double weight) {
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
            double inc = weight / (RRF_K + i + 1);
            rrfScores.merge(key, inc, Double::sum);
        }
    }

    private List<RagSourceVO> weightedRrfFuse(List<WeightedRanking> rankings, int topK) {
        Map<String, RagSourceVO> sourceByKey = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        for (WeightedRanking ranking : rankings) {
            addRrfScores(sourceByKey, scores, ranking.sources(), ranking.weight());
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    RagSourceVO source = copySource(sourceByKey.get(entry.getKey()));
                    source.setScore(entry.getValue());
                    return source;
                })
                .toList();
    }

    private List<RagSourceVO> preserveEvidenceCandidates(
            List<RagSourceVO> fused,
            List<List<RagSourceVO>> candidatePools,
            KnowledgeEvidencePlanner.Plan evidencePlan,
            int maximumCandidates) {
        if (evidencePlan == null || evidencePlan.requirements().isEmpty()) {
            return fused == null ? List.of() : fused;
        }
        List<RagSourceVO> safeFused = fused == null ? List.of() : fused;
        double retrievalFloor = safeFused.stream()
                .map(RagSourceVO::getScore)
                .filter(java.util.Objects::nonNull)
                .filter(Double::isFinite)
                .filter(score -> score > 0.0D)
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(0.001D);
        Map<String, RagSourceVO> protectedByKey = new LinkedHashMap<>();
        for (KnowledgeEvidencePlanner.Requirement requirement : evidencePlan.requirements()) {
            RagSourceVO candidate = firstEvidenceCandidate(candidatePools, requirement);
            if (candidate == null) {
                continue;
            }
            String key = dedupKey(candidate);
            if (!StringUtils.hasText(key) || protectedByKey.containsKey(key)) {
                continue;
            }
            RagSourceVO protectedCandidate = copySource(candidate);
            protectedCandidate.setScore(retrievalFloor);
            protectedByKey.put(key, protectedCandidate);
        }
        for (RagSourceVO source : safeFused) {
            String key = dedupKey(source);
            if (StringUtils.hasText(key)) {
                protectedByKey.put(key, source);
            }
        }
        return protectedByKey.values().stream()
                .limit(Math.max(1, maximumCandidates))
                .toList();
    }

    private RagSourceVO firstEvidenceCandidate(
            List<List<RagSourceVO>> candidatePools,
            KnowledgeEvidencePlanner.Requirement requirement) {
        if (candidatePools == null) {
            return null;
        }
        for (List<RagSourceVO> pool : candidatePools) {
            if (pool == null) {
                continue;
            }
            for (RagSourceVO source : pool) {
                if (source == null) {
                    continue;
                }
                String corpus = String.valueOf(source.getDocTitle()) + " "
                        + String.valueOf(source.getSnippet());
                if (requirement.matches(KnowledgeQueryConcepts.detect(corpus))) {
                    return source;
                }
            }
        }
        return null;
    }

    private record WeightedRanking(List<RagSourceVO> sources, double weight) {
    }

    private record BranchFuture(QueryRewritePlanVO.WeightedQuery branch,
                                CompletableFuture<ChannelHits> vectorFuture,
                                CompletableFuture<ChannelHits> keywordFuture) {
    }

    private record ChannelHits(List<RagSourceVO> sources, String debugInfo, long elapsedMs) {
        private static ChannelHits empty(String debugInfo) {
            return new ChannelHits(List.of(), debugInfo, 0L);
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
            hit.setLogicalKnowledgeId(source.getLogicalKnowledgeId());
            hit.setRetrievalScore(source.getRetrievalScore());
            hit.setRerankScore(source.getRerankScore());
            hit.setRerankReason(source.getRerankReason());
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
        out.setIndexVersion(source.getIndexVersion());
        out.setLogicalKnowledgeId(source.getLogicalKnowledgeId());
        out.setRetrievalScore(source.getRetrievalScore());
        out.setRerankScore(source.getRerankScore());
        out.setRerankReason(source.getRerankReason());
        return out;
    }

    private List<RagSourceVO> applyGovernance(List<RagSourceVO> sources) {
        return applyGovernance(sources, new HashMap<>());
    }

    private List<RagSourceVO> applyGovernance(List<RagSourceVO> sources,
                                               Map<Long, KnowledgeDoc> docCache) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> missingDocIds = new LinkedHashSet<>();
        for (RagSourceVO source : sources) {
            if (source != null && source.getDocId() != null && !docCache.containsKey(source.getDocId())) {
                missingDocIds.add(source.getDocId());
            }
        }
        if (!missingDocIds.isEmpty()) {
            missingDocIds.forEach(docId -> docCache.put(docId, null));
            List<KnowledgeDoc> docs = knowledgeDocMapper.selectBatchIds(missingDocIds);
            if (docs != null) {
                docs.stream()
                        .filter(java.util.Objects::nonNull)
                        .filter(doc -> doc.getId() != null)
                        .forEach(doc -> docCache.put(doc.getId(), doc));
            }
        }
        LocalDateTime now = LocalDateTime.now();
        List<RagSourceVO> out = new ArrayList<>();
        for (RagSourceVO source : sources) {
            if (source == null) {
                continue;
            }
            KnowledgeDoc doc = source.getDocId() == null ? null : docCache.get(source.getDocId());
            if (doc == null) {
                continue;
            }
            if (!isSearchable(doc, now)) {
                continue;
            }
            if (!isActiveIndex(source, doc)) {
                continue;
            }
            enrichGovernance(source, doc);
            out.add(source);
        }
        return out;
    }

    private boolean isSearchable(KnowledgeDoc doc, LocalDateTime now) {
        if (doc == null) {
            return false;
        }
        String status = doc.getStatus();
        if (KnowledgeDocStatus.DISABLED.name().equalsIgnoreCase(status)) {
            return false;
        }
        if (!KnowledgeDocStatus.ACTIVE.name().equalsIgnoreCase(status)) {
            boolean legacyDraft = KnowledgeDocStatus.DRAFT.name().equalsIgnoreCase(status)
                    && !Boolean.TRUE.equals(doc.getLifecycleManaged())
                    && ingestionProperties.getLifecycle().isAllowLegacyDrafts();
            if (!legacyDraft) {
                return false;
            }
            meterRegistry.counter("rag_retrieval_legacy_draft_total").increment();
        } else if (Boolean.TRUE.equals(doc.getLifecycleManaged())
                && !StringUtils.hasText(doc.getActiveIndexVersion())) {
            return false;
        }
        if (doc.getEffectiveFrom() != null && doc.getEffectiveFrom().isAfter(now)) {
            return false;
        }
        return doc.getEffectiveTo() == null || !doc.getEffectiveTo().isBefore(now);
    }

    private boolean isActiveIndex(RagSourceVO source, KnowledgeDoc doc) {
        String sourceVersion = source.getIndexVersion();
        String activeVersion = doc.getActiveIndexVersion();
        if (StringUtils.hasText(sourceVersion)) {
            return StringUtils.hasText(activeVersion) && sourceVersion.equals(activeVersion);
        }
        return !StringUtils.hasText(activeVersion);
    }

    private void enrichGovernance(RagSourceVO source, KnowledgeDoc doc) {
        source.setSourceTrustLevel(firstText(doc.getSourceTrustLevel(), source.getSourceTrustLevel()));
        source.setDocVersion(firstText(doc.getDocVersion(), source.getDocVersion()));
        source.setEffectiveFrom(doc.getEffectiveFrom() == null ? source.getEffectiveFrom() : doc.getEffectiveFrom().toString());
        source.setEffectiveTo(doc.getEffectiveTo() == null ? source.getEffectiveTo() : doc.getEffectiveTo().toString());
        source.setDocStatus(firstText(doc.getStatus(), source.getDocStatus()));
        source.setDisabledReason(firstText(doc.getDisabledReason(), source.getDisabledReason()));
        source.setLogicalKnowledgeId(firstText(doc.getLogicalKnowledgeId(), source.getLogicalKnowledgeId()));
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
