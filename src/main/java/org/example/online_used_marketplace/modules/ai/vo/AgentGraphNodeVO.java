package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class AgentGraphNodeVO {

    private String nodeId;

    /**
     * TOOL / MERGE / FINAL
     */
    private String type;

    private String tool;

    private String thought;

    private List<String> dependsOn = new ArrayList<>();

    private Map<String, Object> input;

    /**
     * PENDING / RUNNING / SUCCESS / FAILED / DENIED / SKIPPED
     */
    private String status = "PENDING";

    private String summary;

    private Long elapsedMs = 0L;
}
