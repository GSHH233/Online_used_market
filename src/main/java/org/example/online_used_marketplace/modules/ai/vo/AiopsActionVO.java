package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiopsActionVO {

    private Long id;

    private Long eventId;

    private String actionType;

    private String status;

    private String suggestion;

    private Boolean executed;

    private String metadataJson;

    private LocalDateTime createdTime;
}
