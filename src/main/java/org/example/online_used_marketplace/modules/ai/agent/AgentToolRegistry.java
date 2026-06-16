package org.example.online_used_marketplace.modules.ai.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> toolList) {
        this.tools = toolList.stream().collect(Collectors.toMap(AgentTool::name, tool -> tool));
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<String> names() {
        return tools.keySet().stream().sorted().toList();
    }
}
