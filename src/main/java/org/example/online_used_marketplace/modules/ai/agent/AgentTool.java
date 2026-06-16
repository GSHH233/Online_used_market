package org.example.online_used_marketplace.modules.ai.agent;

import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

import java.util.Map;

public interface AgentTool {

    String name();

    String description();

    boolean internalOnly();

    ToolQueryResultVO execute(Map<String, Object> input);
}
