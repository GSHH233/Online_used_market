package org.example.online_used_marketplace.modules.ai.vo;

import java.util.List;

public record KnowledgeRerankResult(
        List<RagSourceVO> sources,
        int candidateCount,
        boolean rerankUsed,
        boolean dynamicTopKApplied,
        List<String> queryFacets,
        List<String> coveredFacets,
        double facetCoverage,
        List<String> evidencePlan,
        List<String> coveredEvidence,
        double evidenceCoverage
) {
}
