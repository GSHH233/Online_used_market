package org.example.online_used_marketplace.modules.ai.agent.planner;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentPlanStep {
    private String id;
    private String tool;
    private String thought;
    private Map<String, Object> input = new LinkedHashMap<>();
    private List<String> dependsOn = new ArrayList<>();
}
