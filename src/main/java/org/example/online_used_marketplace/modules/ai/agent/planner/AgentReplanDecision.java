package org.example.online_used_marketplace.modules.ai.agent.planner;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentReplanDecision {
    private boolean replan;
    private int round;
    private String reason;
    private List<String> validationWarnings = new ArrayList<>();
    private List<AgentPlanStep> steps = new ArrayList<>();

    public static AgentReplanDecision stop(int round, String reason) {
        AgentReplanDecision decision = new AgentReplanDecision();
        decision.setRound(round);
        decision.setReason(reason);
        return decision;
    }
}
