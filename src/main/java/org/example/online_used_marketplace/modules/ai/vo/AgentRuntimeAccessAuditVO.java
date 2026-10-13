package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentRuntimeAccessAuditVO {
    private Long id;
    private String runId;
    private String traceId;
    private String actorUserId;
    private String actorRole;
    private String action;
    private String decision;
    private String reason;
    private LocalDateTime createdTime;
}
