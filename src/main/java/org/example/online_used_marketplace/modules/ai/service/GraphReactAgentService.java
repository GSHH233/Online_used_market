package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.GraphAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.GraphAgentResponseVO;

public interface GraphReactAgentService {

    GraphAgentResponseVO run(GraphAgentRequestDTO request);
}
