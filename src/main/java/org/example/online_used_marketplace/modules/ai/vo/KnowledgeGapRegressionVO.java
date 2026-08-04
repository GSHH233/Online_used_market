package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeGapRegressionVO {
    private Long id;
    private Long gapId;
    private Long linkedDocId;
    private Long linkedTaskId;
    private Boolean retrievalHit;
    private Boolean sourceHit;
    private Boolean rejectResolved;
    private Boolean passed;
    private Long latencyMs;
    private Long evalRunId;
    private String traceId;
    private String errorMsg;
    private String detailsJson;
    private LocalDateTime createdTime;
}
