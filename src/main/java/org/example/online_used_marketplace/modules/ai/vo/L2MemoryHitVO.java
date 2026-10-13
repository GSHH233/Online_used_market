package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

@Data
public class L2MemoryHitVO {

    private Long id;
    private String userId;
    private String sessionId;
    private String memoryType;
    private String content;
    private String metadataJson;
    private String sourceEventId;
    private String scopeType;
    private String scopeKey;
    private String slotKey;
    private Long revision;
    private String status;

    private Double score;
    private Double relevanceScore;
    private Double embeddingScore;
    private Double importanceScore;
    private Double tfScore;
    private String retrievalMode;
}
