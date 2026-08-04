package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AiopsRunbookActionVO {

    private String actionCode;

    private String actionName;

    private String riskLevel;

    private Boolean dryRun;

    private Boolean executed;

    private Long eventId;

    private Long actionId;

    private String traceId;

    private String idempotencyKey;

    private String status;

    private String message;

    private String verificationStatus;

    private Long elapsedMs;

    private Map<String, Object> params = new LinkedHashMap<>();

    private Map<String, Object> result = new LinkedHashMap<>();

    private Map<String, Object> metadata = new LinkedHashMap<>();
}
