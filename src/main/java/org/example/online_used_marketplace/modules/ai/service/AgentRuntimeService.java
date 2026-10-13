package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.AgentRuntimeStartRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeRunVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeCheckpointVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeAccessAuditVO;

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
    void recordAccessAudit(String runId, String action, String decision, String reason);
    List<AgentRuntimeAccessAuditVO> accessAudits(String runId, Integer limit);
}
