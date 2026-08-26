package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * 发布助手单次规则检索的可观测摘要。
 *
 * 命中文档仍由 {@link ProductPublishAssistVO#getSources()} 统一承载；本对象只保留
 * 检索方式、过滤条件、通道命中数和耗时，避免把完整命中内容重复返回多次。
 */
@Data
public class PublishAssistRetrievalVO {

    private Integer round;

    private String stage;

    private String purpose;

    private String query;

    private String sourceType;

    private Integer requestedTopK;

    private Integer hitCount;

    /** vector-only / keyword-only / hybrid */
    private String retrievalMode;

    private Integer vectorHitCount;

    private Integer keywordHitCount;

    private Boolean rewriteUsed;

    private String rewriteTrigger;

    private Boolean rerankUsed;

    private Integer rerankCandidateCount;

    private Integer selectedTopK;

    private Boolean dynamicTopKApplied;

    private Long rewriteElapsedMs;

    private Long retrievalElapsedMs;

    private Long branchRetrievalElapsedMs;

    private Long rerankElapsedMs;

    private Double facetCoverage;

    private Double evidenceCoverage;

    /** HIT / EMPTY / FALLBACK_SOURCE / UNAVAILABLE */
    private String outcome;

    private Boolean fallbackSourceUsed;

    private String debugInfo;
}
