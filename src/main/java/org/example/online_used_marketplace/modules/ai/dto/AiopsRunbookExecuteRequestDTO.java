package org.example.online_used_marketplace.modules.ai.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AiopsRunbookExecuteRequestDTO {

    private String actionCode;

    private Long eventId;

    private String idempotencyKey;

    private Boolean dryRun = true;

    private String reason;

    private Map<String, Object> params;
}
