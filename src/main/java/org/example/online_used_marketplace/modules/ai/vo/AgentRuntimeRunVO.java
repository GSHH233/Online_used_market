package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AgentRuntimeRunVO {
    private String runId;
    private String traceId;
    private String ownerUserId;
    private String ownerRole;
    private String strategy;
    private String status;
    private String question;
    private String answer;
    private String errorMsg;
    private Integer maxSteps;
    private Integer completedNodeCount;
    private Integer totalNodeCount;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    private LocalDateTime finishedAt;
    private List<AgentRuntimeNodeVO> nodes = new ArrayList<>();
    private AgentRuntimeCheckpointVO latestCheckpoint;
}
