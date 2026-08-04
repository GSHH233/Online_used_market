package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ControlledOperationVO {

    private Long operationId;
    private String traceId;
    private String actionCode;
    private String riskLevel;
    private String status;
    private String reason;
    private String idempotencyKey;
    private Map<String, Object> params = new LinkedHashMap<>();
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private Boolean manualRequired;
    private String escalationReason;
    private AiopsRunbookActionVO execution;
}
