package org.example.online_used_marketplace.modules.ai.agent.planner;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentPlan {
    private String plannerType;
    private String reason;
    private boolean fallback;
    private List<String> validationWarnings = new ArrayList<>();
    private List<AgentPlanStep> steps = new ArrayList<>();
}
