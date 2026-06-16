package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * RAG检索来源
 */
@Data
public class RagSourceVO {
    private Long docId;
    private String docTitle;
    private Long chunkId;
    private String snippet;
    private Double score;
    private String sourceType;
    private String sourceUri;
    private String sourceTrustLevel;
    private String docVersion;
    private String effectiveFrom;
    private String effectiveTo;
    private String docStatus;
    private String disabledReason;
}
