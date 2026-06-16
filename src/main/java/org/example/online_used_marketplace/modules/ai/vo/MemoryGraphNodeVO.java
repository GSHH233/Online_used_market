package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class MemoryGraphNodeVO {

    private Long id;

    private String userId;

    private String sessionId;

    private String nodeType;

    private String label;

    private String content;

    private String topic;

    private Double importance;

    private Double confidence;

    private String source;

    private Map<String, Object> metadata;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
