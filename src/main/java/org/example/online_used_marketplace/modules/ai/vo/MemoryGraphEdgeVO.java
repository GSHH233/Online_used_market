package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class MemoryGraphEdgeVO {

    private Long id;

    private String userId;

    private String sessionId;

    private Long fromNodeId;

    private Long toNodeId;

    private String fromLabel;

    private String toLabel;

    private String edgeType;

    private Double weight;

    private Double confidence;

    private String evidence;

    private String source;

    private Map<String, Object> metadata;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
