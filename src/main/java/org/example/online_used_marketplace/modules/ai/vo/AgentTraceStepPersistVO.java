package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentTraceStepPersistVO {

    private Long id;

    private Long runId;

    private String traceId;

    private Integer stepIndex;

    private String phase;

    private String tool;

    private String status;

    private String thought;

    private String action;

    private String observation;

    private Long elapsedMs;

    private String sourceDocIdsJson;

    private String metadataJson;

    private LocalDateTime createdTime;
}
