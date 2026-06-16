package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * 知识检索命中项
 */
@Data
public class KnowledgeSearchHitVO {

    private Long docId;

    private Long chunkId;

    private String snippet;

    private String docTitle;

    private String sourceType;

    private String sourceUri;

    private String sourceTrustLevel;

    private String docVersion;

    private String effectiveFrom;

    private String effectiveTo;

    private String docStatus;

    private String disabledReason;

    private Double score;

    private String metadata;
}
