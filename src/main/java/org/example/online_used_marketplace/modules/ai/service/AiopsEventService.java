package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.AiopsEventCreateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventUpdateDTO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;

import java.util.List;

public interface AiopsEventService {

    AiopsEventVO createOrUpdate(AiopsEventCreateDTO dto);

    AiopsEventVO update(Long eventId, AiopsEventUpdateDTO dto);

    AiopsEventVO get(Long eventId);

    List<AiopsEventVO> list(String status, String severity, Integer limit);
}
