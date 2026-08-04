package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeGapVO {
    private Long id;
    private String fingerprint;
    private String query;
    private String normalizedQuery;
    private String reasonCode;
    private String status;
    private String priority;
    private Integer occurrenceCount;
    private Integer recurrenceCount;
    private Long linkedDocId;
    private Long linkedTaskId;
    private String latestTraceId;
    private String latestSessionId;
    private String metadataJson;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime resolvedAt;
}
