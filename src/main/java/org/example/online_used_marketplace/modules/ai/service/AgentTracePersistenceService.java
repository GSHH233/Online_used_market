package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.AgentTraceQueryDTO;
import org.example.online_used_marketplace.modules.ai.dto.GraphAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ReactAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.AgentTraceRunVO;
import org.example.online_used_marketplace.modules.ai.vo.GraphAgentResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ReactAgentResponseVO;

import java.util.List;
import java.util.Map;

public interface AgentTracePersistenceService {

    void saveReactTrace(ReactAgentRequestDTO request, ReactAgentResponseVO response, long startedMs);

    void saveGraphTrace(GraphAgentRequestDTO request, GraphAgentResponseVO response, long startedMs);

    void saveReactFailure(ReactAgentRequestDTO request, Throwable error, long startedMs);

    void saveGraphFailure(GraphAgentRequestDTO request, Throwable error, long startedMs);

    AgentTraceRunVO getByTraceId(String traceId);

    List<AgentTraceRunVO> list(AgentTraceQueryDTO query);

    Map<String, Object> stats();
}
