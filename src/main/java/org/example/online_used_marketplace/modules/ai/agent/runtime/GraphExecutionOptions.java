package org.example.online_used_marketplace.modules.ai.agent.runtime;

import lombok.Getter;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

import java.util.Map;

@Getter
public class GraphExecutionOptions {
    private static final GraphExecutionOptions EMPTY = new GraphExecutionOptions(new GraphExecutionObserver() { }, Map.of(), null);
    private final GraphExecutionObserver observer;
    private final Map<String, ToolQueryResultVO> completedToolObservations;
    private final AgentExecutionIdentity identity;

    public GraphExecutionOptions(GraphExecutionObserver observer,
                                 Map<String, ToolQueryResultVO> completedToolObservations) {
        this(observer, completedToolObservations, null);
    }

    public GraphExecutionOptions(GraphExecutionObserver observer,
                                 Map<String, ToolQueryResultVO> completedToolObservations,
                                 AgentExecutionIdentity identity) {
        this.observer = observer == null ? new GraphExecutionObserver() { } : observer;
        this.completedToolObservations = completedToolObservations == null ? Map.of() : completedToolObservations;
        this.identity = identity;
    }

    public static GraphExecutionOptions empty() { return EMPTY; }
}
