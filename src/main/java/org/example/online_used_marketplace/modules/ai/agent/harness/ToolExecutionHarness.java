package org.example.online_used_marketplace.modules.ai.agent.harness;

import org.example.online_used_marketplace.modules.ai.agent.AgentTool;

import java.util.Map;

public interface ToolExecutionHarness {

    ToolExecutionResult execute(AgentTool tool, Map<String, Object> input, String mode);
}
