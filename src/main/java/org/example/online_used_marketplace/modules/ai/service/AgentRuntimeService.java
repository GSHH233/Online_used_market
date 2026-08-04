package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.AgentRuntimeStartRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeRunVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeCheckpointVO;

import java.util.List;

public interface AgentRuntimeService {
    AgentRuntimeRunVO start(AgentRuntimeStartRequestDTO request);
    AgentRuntimeRunVO get(String runId);
    List<AgentRuntimeRunVO> list(Integer limit);
    AgentRuntimeRunVO resume(String runId);
    AgentRuntimeRunVO pause(String runId);
    AgentRuntimeRunVO cancel(String runId);
    AgentRuntimeRunVO replay(String runId, boolean liveReadonly);
    List<AgentRuntimeCheckpointVO> checkpoints(String runId);
}
