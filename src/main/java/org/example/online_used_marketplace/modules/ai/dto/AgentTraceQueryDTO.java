package org.example.online_used_marketplace.modules.ai.dto;

import lombok.Data;

@Data
public class AgentTraceQueryDTO {

    private String traceId;

    private String sessionId;

    private String agentType;

    private String mode;

    private String status;

    private String tool;

    private Integer limit = 20;
}
