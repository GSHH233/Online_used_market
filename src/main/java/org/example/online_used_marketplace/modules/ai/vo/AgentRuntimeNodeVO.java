package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;
import java.util.List;

@Data
public class AgentRuntimeNodeVO {
    private String nodeId;
    private String toolName;
    private String nodeType;
    private List<String> dependencies;
    private String status;
    private Integer attemptCount;
    private Long elapsedMs;
    private String observationJson;
    private String errorMsg;
}
