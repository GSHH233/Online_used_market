package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeReranker;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeRerankResult;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DeterministicKnowledgeReranker implements KnowledgeReranker {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+(?:[._+\\-][a-z0-9]+)*|[\\p{IsHan}]+");
    private static final Set<String> STOP_TERMS = Set.of(
            "这个", "那个", "一个", "一种", "什么", "怎么", "是否", "可以", "不能", "需要", "应该",
            "直接", "现在", "已经", "还是", "但是", "如果", "因为", "所以", "时候", "发布", "商品",
            "二手", "问题", "情况", "进行", "相关", "用户", "平台", "规则", "风险", "描述", "说明",
            "信息", "内容", "建议");
    private final KnowledgeIngestionProperties properties;

    @Override
    public KnowledgeRerankResult rerank(String query, List<RagSourceVO> candidates, int requestedTopK) {
        List<RagSourceVO> safeCandidates = candidates == null
                ? new ArrayList<>()
                : new ArrayList<>(candidates);
        int limit = Math.max(1, requestedTopK);
        KnowledgeEvidencePlanner.Plan evidencePlan = KnowledgeEvidencePlanner.plan(query);
        Set<String> queryTerms = new LinkedHashSet<>(terms(query));
        queryTerms.addAll(evidencePlan.normalizedConcepts());
        Set<String> queryConcepts = new LinkedHashSet<>(evidencePlan.normalizedConcepts());
        Set<String> queryFacets = KnowledgeQueryConcepts.coverageTargets(queryConcepts);
        if (safeCandidates.isEmpty()) {
            return result(List.of(), 0, properties.getRetrieval().isRerankEnabled(), false,
                    queryFacets, Set.of(), evidencePlan, Set.of());
        }
        if (!properties.getRetrieval().isRerankEnabled()) {
            List<RagSourceVO> selected = safeCandidates.stream().limit(limit).toList();
            return result(selected, safeCandidates.size(), false, false,
                    queryFacets, coveredFacets(selected, queryFacets),
                    evidencePlan, coveredEvidence(selected, evidencePlan));
        }

        double maxRetrievalScore = safeCandidates.stream()
                .map(RagSourceVO::getScore)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(1.0D);
        if (maxRetrievalScore <= 0) {
            maxRetrievalScore = 1.0D;
        }

        List<ScoredCandidate> scored = new ArrayList<>();
        for (RagSourceVO source : safeCandidates) {
            Set<String> contentTerms = terms(source.getSnippet());
            Set<String> titleTerms = terms(source.getDocTitle());
            Set<String> candidateTerms = new LinkedHashSet<>(titleTerms);
            candidateTerms.addAll(contentTerms);
            Set<String> candidateConcepts = concepts(candidateTerms);
            double retrieval = clamp(score(source.getScore()) / maxRetrievalScore);
            double content = relevance(queryTerms, contentTerms);
            double title = relevance(queryTerms, titleTerms);
            double businessConcept = conceptRelevance(queryTerms, candidateTerms);
            double trust = trustScore(source.getSourceTrustLevel());
            double conceptWeight = concepts(queryTerms).isEmpty()
                    ? 0.0D
                    : Math.max(0.0D, properties.getRetrieval().getConceptWeight());
            double totalWeight = properties.getRetrieval().getRetrievalWeight()
                    + properties.getRetrieval().getContentWeight()
                    + properties.getRetrieval().getTitleWeight()
                    + properties.getRetrieval().getTrustWeight()
                    + conceptWeight;
            double rerank = totalWeight <= 0 ? retrieval : (
                    retrieval * properties.getRetrieval().getRetrievalWeight()
                            + content * properties.getRetrieval().getContentWeight()
                            + title * properties.getRetrieval().getTitleWeight()
                            + trust * properties.getRetrieval().getTrustWeight()
                            + businessConcept * conceptWeight
            ) / totalWeight;
            if (KnowledgeQueryConcepts.hasCategoryConflict(queryConcepts, candidateConcepts)) {
                rerank *= 0.60D;
            }
            if (properties.getRetrieval().isFacetCoverageEnabled()
                    && !queryFacets.isEmpty()
                    && disjoint(queryFacets, candidateConcepts)) {
                rerank *= clamp(properties.getRetrieval().getFacetMismatchPenalty());
            }
            source.setRetrievalScore(source.getScore());
            source.setRerankScore(rerank);
            source.setScore(rerank);
            source.setRerankReason("retrieval=%.3f,content=%.3f,title=%.3f,concept=%.3f,trust=%.3f,facets=%s"
                    .formatted(retrieval, content, title, businessConcept, trust,
                            KnowledgeQueryConcepts.coverageTargets(candidateConcepts)));
            scored.add(new ScoredCandidate(source, rerank, candidateTerms, candidateConcepts));
        }
        scored.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());

        double bestScore = scored.get(0).score();
        double dynamicThreshold = Math.max(
                properties.getRetrieval().getMinRerankScore(),
                bestScore * properties.getRetrieval().getRelativeScoreThreshold());
        int minimum = inferMinimumResults(query, queryFacets, evidencePlan, limit);
        int maxPerLogical = Math.max(1, properties.getRetrieval().getMaxPerLogicalKnowledge());
        double lambda = clamp(properties.getRetrieval().getDiversityLambda());
        List<ScoredCandidate> remaining = new ArrayList<>(scored);
        List<ScoredCandidate> selected = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        Set<String> coveredEvidence = new LinkedHashSet<>();
        Map<String, Integer> logicalCounts = new HashMap<>();

        while (!remaining.isEmpty() && selected.size() < limit) {
            List<ScoredCandidate> eligible = remaining.stream()
                    .filter(candidate -> {
                        String key = logicalKey(candidate);
                        return logicalCounts.getOrDefault(key, 0) < maxForKey(key, maxPerLogical);
                    })
                    .toList();
            Comparator<ScoredCandidate> bySelectionScore = Comparator.comparingDouble(candidate -> selectionScore(
                    candidate, selected, queryFacets, covered,
                    evidencePlan, coveredEvidence, lambda));
            boolean evidenceIncomplete = properties.getRetrieval().isEvidencePlanEnabled()
                    && evidenceCoverage(evidencePlan, coveredEvidence)
                    < properties.getRetrieval().getMinEvidenceCoverage();
            boolean facetIncomplete = properties.getRetrieval().isFacetCoverageEnabled()
                    && coverageRatio(queryFacets, covered) < properties.getRetrieval().getMinFacetCoverage();

            ScoredCandidate next = evidenceIncomplete
                    ? eligible.stream()
                    .filter(candidate -> evidenceGain(evidencePlan, coveredEvidence, candidate.concepts()) > 0.0D)
                    .max(bySelectionScore)
                    .orElse(null)
                    : null;
            if (next == null && facetIncomplete) {
                next = eligible.stream()
                        .filter(candidate -> facetGain(queryFacets, covered, candidate.concepts()) > 0.0D)
                        .max(bySelectionScore)
                        .orElse(null);
            }
            if (next == null) {
                next = eligible.stream().max(bySelectionScore).orElse(null);
            }
            if (next == null) {
                break;
            }
            double nextFacetGain = facetGain(queryFacets, covered, next.concepts());
            boolean shouldCompleteCoverage = properties.getRetrieval().isFacetCoverageEnabled()
                    && coverageRatio(queryFacets, covered) < properties.getRetrieval().getMinFacetCoverage()
                    && nextFacetGain > 0.0D;
            double nextEvidenceGain = evidenceGain(evidencePlan, coveredEvidence, next.concepts());
            boolean shouldCompleteEvidence = properties.getRetrieval().isEvidencePlanEnabled()
                    && evidenceCoverage(evidencePlan, coveredEvidence)
                    < properties.getRetrieval().getMinEvidenceCoverage()
                    && nextEvidenceGain > 0.0D;
            if (properties.getRetrieval().isDynamicTopKEnabled()
                    && selected.size() >= minimum
                    && next.score() < dynamicThreshold
                    && !shouldCompleteCoverage
                    && !shouldCompleteEvidence) {
                break;
            }
            selected.add(next);
            remaining.remove(next);
            addCoveredFacets(covered, queryFacets, next.concepts());
            addCoveredEvidence(coveredEvidence, evidencePlan, next.concepts());
            logicalCounts.merge(logicalKey(next), 1, Integer::sum);
        }
        if (selected.size() < minimum) {
            for (ScoredCandidate candidate : scored) {
                if (selected.contains(candidate)) {
                    continue;
                }
                String key = logicalKey(candidate);
                if (logicalCounts.getOrDefault(key, 0) >= maxForKey(key, maxPerLogical)) {
                    continue;
                }
                selected.add(candidate);
                addCoveredFacets(covered, queryFacets, candidate.concepts());
                addCoveredEvidence(coveredEvidence, evidencePlan, candidate.concepts());
                logicalCounts.merge(key, 1, Integer::sum);
                if (selected.size() >= minimum || selected.size() >= limit) {
                    break;
                }
            }
        }

        List<RagSourceVO> output = selected.stream().map(ScoredCandidate::source).toList();
        boolean dynamicApplied = properties.getRetrieval().isDynamicTopKEnabled()
                && output.size() < Math.min(limit, scored.size());
        return result(output, scored.size(), true, dynamicApplied,
                queryFacets, covered, evidencePlan, coveredEvidence);
    }

    private double selectionScore(ScoredCandidate candidate,
                                  List<ScoredCandidate> selected,
                                  Set<String> queryFacets,
                                  Set<String> coveredFacets,
                                  KnowledgeEvidencePlanner.Plan evidencePlan,
                                  Set<String> coveredEvidence,
                                  double lambda) {
        double score = mmr(candidate, selected, lambda);
        if (properties.getRetrieval().isFacetCoverageEnabled()) {
            score += Math.max(0.0D, properties.getRetrieval().getFacetCoverageWeight())
                    * facetGain(queryFacets, coveredFacets, candidate.concepts());
        }
        if (properties.getRetrieval().isEvidencePlanEnabled()) {
            score += Math.max(0.0D, properties.getRetrieval().getEvidencePlanWeight())
                    * evidenceGain(evidencePlan, coveredEvidence, candidate.concepts());
        }
        return score;
    }

    private double mmr(ScoredCandidate candidate, List<ScoredCandidate> selected, double lambda) {
        double maxSimilarity = selected.stream()
                .mapToDouble(existing -> jaccard(candidate.terms(), existing.terms()))
                .max()
                .orElse(0.0D);
        return lambda * candidate.score() - (1.0D - lambda) * maxSimilarity;
    }

    private double facetGain(Set<String> queryFacets,
                             Set<String> coveredFacets,
                             Set<String> candidateConcepts) {
        if (queryFacets.isEmpty() || candidateConcepts.isEmpty()) {
            return 0.0D;
        }
        Set<String> newlyCovered = new LinkedHashSet<>(queryFacets);
        newlyCovered.removeAll(coveredFacets);
        newlyCovered.retainAll(candidateConcepts);
        double totalWeight = queryFacets.stream().mapToDouble(KnowledgeQueryConcepts::weight).sum();
        double gainWeight = newlyCovered.stream().mapToDouble(KnowledgeQueryConcepts::weight).sum();
        return totalWeight <= 0.0D ? 0.0D : gainWeight / totalWeight;
    }

    private double coverageRatio(Set<String> queryFacets, Set<String> coveredFacets) {
        return KnowledgeQueryConcepts.coverageRatio(queryFacets, coveredFacets);
    }

    private double evidenceGain(KnowledgeEvidencePlanner.Plan plan,
                                Set<String> covered,
                                Set<String> candidateConcepts) {
        double totalWeight = plan.requirements().stream()
                .mapToDouble(KnowledgeEvidencePlanner.Requirement::weight)
                .sum();
        double gainWeight = plan.requirements().stream()
                .filter(requirement -> !covered.contains(requirement.id()))
                .filter(requirement -> requirement.matches(candidateConcepts))
                .mapToDouble(KnowledgeEvidencePlanner.Requirement::weight)
                .sum();
        return totalWeight <= 0.0D ? 0.0D : gainWeight / totalWeight;
    }

    private double evidenceCoverage(KnowledgeEvidencePlanner.Plan plan, Set<String> covered) {
        if (plan.requirements().isEmpty()) {
            return 1.0D;
        }
        double totalWeight = plan.requirements().stream()
                .mapToDouble(KnowledgeEvidencePlanner.Requirement::weight)
                .sum();
        double coveredWeight = plan.requirements().stream()
                .filter(requirement -> covered.contains(requirement.id()))
                .mapToDouble(KnowledgeEvidencePlanner.Requirement::weight)
                .sum();
        return totalWeight <= 0.0D ? 1.0D : clamp(coveredWeight / totalWeight);
    }

    private void addCoveredFacets(Set<String> covered,
                                  Set<String> queryFacets,
                                  Set<String> candidateConcepts) {
        for (String concept : candidateConcepts) {
            if (queryFacets.contains(concept)) {
                covered.add(concept);
            }
        }
    }

    private Set<String> coveredFacets(List<RagSourceVO> sources, Set<String> queryFacets) {
        Set<String> covered = new LinkedHashSet<>();
        for (RagSourceVO source : sources) {
            String corpus = String.valueOf(source.getDocTitle()) + " " + String.valueOf(source.getSnippet());
            addCoveredFacets(covered, queryFacets, KnowledgeQueryConcepts.detect(corpus));
        }
        return covered;
    }

    private void addCoveredEvidence(Set<String> covered,
                                    KnowledgeEvidencePlanner.Plan plan,
                                    Set<String> candidateConcepts) {
        for (KnowledgeEvidencePlanner.Requirement requirement : plan.requirements()) {
            if (requirement.matches(candidateConcepts)) {
                covered.add(requirement.id());
            }
        }
    }

    private Set<String> coveredEvidence(List<RagSourceVO> sources,
                                        KnowledgeEvidencePlanner.Plan plan) {
        Set<String> covered = new LinkedHashSet<>();
        for (RagSourceVO source : sources) {
            String corpus = String.valueOf(source.getDocTitle()) + " " + String.valueOf(source.getSnippet());
            addCoveredEvidence(covered, plan, KnowledgeQueryConcepts.detect(corpus));
        }
        return covered;
    }

    private boolean disjoint(Set<String> left, Set<String> right) {
        return left.stream().noneMatch(right::contains);
    }

    private KnowledgeRerankResult result(List<RagSourceVO> sources,
                                         int candidateCount,
                                         boolean rerankUsed,
                                         boolean dynamicTopKApplied,
                                         Set<String> queryFacets,
                                         Set<String> coveredFacets,
                                         KnowledgeEvidencePlanner.Plan evidencePlan,
                                         Set<String> coveredEvidence) {
        return new KnowledgeRerankResult(
                sources,
                candidateCount,
                rerankUsed,
                dynamicTopKApplied,
                List.copyOf(queryFacets),
                List.copyOf(coveredFacets),
                coverageRatio(queryFacets, coveredFacets),
                evidencePlan.requirements().stream()
                        .map(KnowledgeEvidencePlanner.Requirement::id)
                        .toList(),
                List.copyOf(coveredEvidence),
                evidenceCoverage(evidencePlan, coveredEvidence));
    }

    private Set<String> terms(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalizedText);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.codePoints().allMatch(DeterministicKnowledgeReranker::isHan)) {
                List<Integer> chars = token.codePoints().boxed().toList();
                if (chars.size() == 1) {
                    if (!STOP_TERMS.contains(token)) {
                        result.add(token);
                    }
                } else {
                    for (int i = 0; i < chars.size() - 1; i++) {
                        String bigram = new String(new int[]{chars.get(i), chars.get(i + 1)}, 0, 2);
                        if (!STOP_TERMS.contains(bigram)) {
                            result.add(bigram);
                        }
                    }
                }
            } else if (token.length() > 1 && !STOP_TERMS.contains(token)) {
                result.add(token);
            }
        }
        result.addAll(KnowledgeQueryConcepts.detect(normalizedText));
        return result;
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private double relevance(Set<String> queryTerms, Set<String> candidateTerms) {
        if (queryTerms.isEmpty() || candidateTerms.isEmpty()) {
            return 0.0D;
        }
        Set<String> queryConcepts = concepts(queryTerms);
        Set<String> candidateConcepts = concepts(candidateTerms);
        Set<String> queryLexical = lexical(queryTerms);
        Set<String> candidateLexical = lexical(candidateTerms);
        Set<String> intersection = new HashSet<>(queryLexical);
        intersection.retainAll(candidateLexical);
        double lexicalCoverage = queryLexical.isEmpty()
                ? 0.0D
                : intersection.size() / (double) queryLexical.size();
        if (queryConcepts.isEmpty()) {
            return clamp(0.75D * lexicalCoverage + 0.25D * jaccard(queryLexical, candidateLexical));
        }
        double conceptCoverage = conceptRelevance(queryConcepts, candidateConcepts);
        return clamp(0.40D * lexicalCoverage
                + 0.10D * jaccard(queryLexical, candidateLexical)
                + 0.50D * conceptCoverage);
    }

    private double conceptRelevance(Set<String> queryTerms, Set<String> candidateTerms) {
        Set<String> queryConcepts = concepts(queryTerms);
        if (queryConcepts.isEmpty()) {
            return 0.0D;
        }
        Set<String> candidateConcepts = concepts(candidateTerms);
        Set<String> conceptIntersection = new HashSet<>(queryConcepts);
        conceptIntersection.retainAll(candidateConcepts);
        double totalConceptWeight = queryConcepts.stream()
                .mapToDouble(KnowledgeQueryConcepts::weight)
                .sum();
        double matchedConceptWeight = conceptIntersection.stream()
                .mapToDouble(KnowledgeQueryConcepts::weight)
                .sum();
        return totalConceptWeight <= 0.0D
                ? 0.0D
                : matchedConceptWeight / totalConceptWeight;
    }

    private int inferMinimumResults(String query,
                                    Set<String> queryFacets,
                                    KnowledgeEvidencePlanner.Plan evidencePlan,
                                    int limit) {
        int configured = Math.max(1, properties.getRetrieval().getMinResults());
        long conceptCount = queryFacets.size();
        String normalized = StringUtils.hasText(query) ? query.toLowerCase(Locale.ROOT) : "";
        boolean multiEvidenceIntent = evidencePlan.multiHop()
                || conceptCount >= 2
                || normalized.contains("哪些因素")
                || normalized.contains("分别")
                || normalized.contains("同时")
                || normalized.contains("还缺")
                || normalized.contains("但不知道")
                || normalized.contains("也有风险");
        int inferred = multiEvidenceIntent ? 2 : 1;
        return Math.min(Math.max(configured, inferred), limit);
    }

    private Set<String> concepts(Set<String> terms) {
        Set<String> result = new LinkedHashSet<>();
        for (String term : terms) {
            if (term.startsWith("@")) {
                result.add(term);
            }
        }
        return result;
    }

    private Set<String> lexical(Set<String> terms) {
        Set<String> result = new HashSet<>();
        for (String term : terms) {
            if (!term.startsWith("@")) {
                result.add(term);
            }
        }
        return result;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0D;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0D : intersection.size() / (double) union.size();
    }

    private double trustScore(String trustLevel) {
        if (!StringUtils.hasText(trustLevel)) {
            return 0.6D;
        }
        return switch (trustLevel.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH" -> 1.0D;
            case "MEDIUM" -> 0.7D;
            case "LOW" -> 0.3D;
            default -> 0.5D;
        };
    }

    private String logicalKey(ScoredCandidate candidate) {
        String semanticFamily = semanticFamilyKey(candidate.concepts());
        if (StringUtils.hasText(semanticFamily)) {
            return semanticFamily;
        }
        RagSourceVO source = candidate.source();
        return StringUtils.hasText(source.getLogicalKnowledgeId())
                ? "logical:" + source.getLogicalKnowledgeId()
                : "doc:" + source.getDocId();
    }

    private int maxForKey(String key, int configuredMaximum) {
        return key.startsWith("family:") ? 1 : configuredMaximum;
    }

    private String semanticFamilyKey(Set<String> concepts) {
        if (concepts.contains("@off_platform")) {
            return "family:off_platform_trade";
        }
        if (concepts.contains("@account")) {
            return "family:account_trade";
        }
        if (concepts.contains("@identifier_tampering")) {
            return "family:identifier_tampering";
        }
        if (concepts.contains("@tobacco")) {
            return "family:tobacco";
        }
        if (concepts.contains("@controlled_weapon")) {
            return "family:controlled_weapon";
        }
        if (concepts.contains("@medical")) {
            return "family:medical";
        }
        if (concepts.contains("@sim_card")) {
            return "family:sim_card";
        }
        if (concepts.contains("@citation")) {
            return "family:evidence_citation";
        }
        return "";
    }

    private double score(Double value) {
        return value == null || !Double.isFinite(value) ? 0.0D : value;
    }

    private double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private record ScoredCandidate(RagSourceVO source,
                                   double score,
                                   Set<String> terms,
                                   Set<String> concepts) {
    }
}
