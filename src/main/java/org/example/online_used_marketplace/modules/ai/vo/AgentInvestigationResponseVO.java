package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentInvestigationResponseVO {

    private String traceId;

    private String strategy;

    private String routeReason;

    private String status;

    private String question;

    private String answer;

    private Boolean success;

    private Boolean approvalRequired = false;

    private Long elapsedMs;

    private List<AgentTraceStepVO> agentTrace = new ArrayList<>();

    private List<ToolQueryResultVO> observations = new ArrayList<>();

    private List<RagSourceVO> sources = new ArrayList<>();

    private List<AgentGraphNodeVO> graph = new ArrayList<>();
}
