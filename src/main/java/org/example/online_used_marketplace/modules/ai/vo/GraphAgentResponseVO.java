package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GraphAgentResponseVO {

    private String traceId;

    private String question;

    private String mode;

    private String answer;

    private List<AgentGraphNodeVO> graph = new ArrayList<>();

    private List<AgentTraceStepVO> agentTrace = new ArrayList<>();

    private List<ToolQueryResultVO> observations = new ArrayList<>();

    private List<RagSourceVO> sources = new ArrayList<>();

    private Boolean success;

    private Long elapsedMs;

    private String debugInfo;
}
