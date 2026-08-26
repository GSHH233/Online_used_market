package org.example.online_used_marketplace.modules.ai.pipeline;

import org.example.online_used_marketplace.modules.ai.context.PublishAssistResult;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistRetrievalVO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 将统一知识检索响应转换为发布助手可展示的检索观测。
 */
@Component
public class PublishAssistRetrievalTraceHelper {

    public PublishAssistRetrievalVO toRetrievalTrace(String stage,
                                                      String purpose,
                                                      Integer round,
                                                      KnowledgeSourceType sourceType,
                                                      String requestedQuery,
                                                      Integer requestedTopK,
                                                      KnowledgeSearchResponseVO response,
                                                      boolean fallbackSourceUsed) {
        PublishAssistRetrievalVO trace = new PublishAssistRetrievalVO();
        trace.setRound(round);
        trace.setStage(stage);
        trace.setPurpose(purpose);
        trace.setSourceType(sourceType == null ? null : sourceType.name());
        trace.setRequestedTopK(requestedTopK);
        trace.setFallbackSourceUsed(fallbackSourceUsed);

        if (response == null) {
            trace.setQuery(requestedQuery);
            trace.setHitCount(0);
            trace.setVectorHitCount(0);
            trace.setKeywordHitCount(0);
            trace.setOutcome("UNAVAILABLE");
            trace.setDebugInfo("knowledgeSearchResponse=null");
            return trace;
        }

        trace.setQuery(StringUtils.hasText(response.getQuery()) ? response.getQuery() : requestedQuery);
        trace.setRequestedTopK(response.getTopK() == null ? requestedTopK : response.getTopK());
        trace.setHitCount(response.getTotal() == null
                ? (response.getHits() == null ? 0 : response.getHits().size())
                : response.getTotal());
        trace.setRetrievalMode(response.getRetrievalMode());
        trace.setVectorHitCount(zeroIfNull(response.getVectorHitCount()));
        trace.setKeywordHitCount(zeroIfNull(response.getKeywordHitCount()));
        trace.setRewriteUsed(response.getRewriteUsed());
        trace.setRewriteTrigger(response.getRewriteTrigger());
        trace.setRerankUsed(response.getRerankUsed());
        trace.setRerankCandidateCount(response.getRerankCandidateCount());
        trace.setSelectedTopK(response.getSelectedTopK());
        trace.setDynamicTopKApplied(response.getDynamicTopKApplied());
        trace.setRewriteElapsedMs(response.getRewriteElapsedMs());
        trace.setRetrievalElapsedMs(response.getRetrievalElapsedMs());
        trace.setBranchRetrievalElapsedMs(response.getBranchRetrievalElapsedMs());
        trace.setRerankElapsedMs(response.getRerankElapsedMs());
        trace.setFacetCoverage(response.getFacetCoverage());
        trace.setEvidenceCoverage(response.getEvidenceCoverage());
        trace.setDebugInfo(response.getDebugInfo());
        trace.setOutcome(resolveOutcome(trace.getHitCount(), fallbackSourceUsed));
        return trace;
    }

    public void append(PublishAssistResult result, PublishAssistRetrievalVO trace) {
        List<PublishAssistRetrievalVO> retrievals = result.getRetrievals() == null
                ? new ArrayList<>() : new ArrayList<>(result.getRetrievals());
        retrievals.add(trace);
        result.setRetrievals(retrievals);
    }

    private Integer zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    private String resolveOutcome(Integer hitCount, boolean fallbackSourceUsed) {
        if (fallbackSourceUsed) {
            return "FALLBACK_SOURCE";
        }
        return hitCount != null && hitCount > 0 ? "HIT" : "EMPTY";
    }
}
