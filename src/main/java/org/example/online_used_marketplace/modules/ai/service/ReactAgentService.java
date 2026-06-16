package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.ReactAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ReactAgentResponseVO;

public interface ReactAgentService {

    ReactAgentResponseVO run(ReactAgentRequestDTO request);
}
