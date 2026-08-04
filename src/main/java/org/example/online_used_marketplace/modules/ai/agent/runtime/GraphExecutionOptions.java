package org.example.online_used_marketplace.modules.ai.agent.runtime;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

import java.util.Map;

@Getter
@RequiredArgsConstructor
public class GraphExecutionOptions {
    private static final GraphExecutionOptions EMPTY = new GraphExecutionOptions(new GraphExecutionObserver() { }, Map.of());
    private final GraphExecutionObserver observer;
    private final Map<String, ToolQueryResultVO> completedToolObservations;
    public static GraphExecutionOptions empty() { return EMPTY; }
}
