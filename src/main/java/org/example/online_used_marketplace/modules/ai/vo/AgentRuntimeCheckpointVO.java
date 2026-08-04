package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentRuntimeCheckpointVO {
    private Integer checkpointSeq;
    private String reason;
    private String stateJson;
    private String stateHash;
    private LocalDateTime createdTime;
}
