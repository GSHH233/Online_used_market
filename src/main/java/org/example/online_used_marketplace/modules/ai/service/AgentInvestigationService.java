package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.AgentInvestigationRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.AgentInvestigationResponseVO;

public interface AgentInvestigationService {

    AgentInvestigationResponseVO investigate(AgentInvestigationRequestDTO request);
}
