package org.example.online_used_marketplace.modules.ai.alert;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiopsChangeEventVO {

    private Long id;

    private String service;

    private String environment;

    private String changeType;

    private String version;

    private String traceId;

    private String summary;

    private String metadataJson;

    private LocalDateTime occurredAt;

    private LocalDateTime createdTime;
}
