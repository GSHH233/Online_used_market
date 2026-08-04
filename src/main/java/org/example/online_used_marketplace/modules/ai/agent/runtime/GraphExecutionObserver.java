package org.example.online_used_marketplace.modules.ai.agent.runtime;

import org.example.online_used_marketplace.modules.ai.vo.AgentGraphNodeVO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

import java.util.List;

public interface GraphExecutionObserver {
    default void onPlan(String traceId, List<AgentGraphNodeVO> nodes) { }
    default void onNodeStarted(AgentGraphNodeVO node) { }
    default void onNodeFinished(AgentGraphNodeVO node, ToolQueryResultVO observation) { }
}
