package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiopsActionVO {

    private Long id;

    private Long eventId;

    private String actionType;

    private String traceId;

    private String idempotencyKey;

    private String status;

    private String verificationStatus;

    private String suggestion;

    private Boolean executed;

    private String metadataJson;

    private LocalDateTime createdTime;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
