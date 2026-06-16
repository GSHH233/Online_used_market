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

    private Double score;
    private Double embeddingScore;
    private Double importanceScore;
    private Double tfScore;
}
