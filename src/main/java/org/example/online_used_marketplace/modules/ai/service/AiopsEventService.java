package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.AiopsEventCreateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventUpdateDTO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsMttrMetricsVO;

import java.time.LocalDateTime;
import java.util.List;

public interface AiopsEventService {

    AiopsEventVO createOrUpdate(AiopsEventCreateDTO dto);

    AiopsEventVO update(Long eventId, AiopsEventUpdateDTO dto);

    AiopsEventVO get(Long eventId);

    List<AiopsEventVO> list(String status, String severity, Integer limit);

    AiopsEventVO approve(Long eventId, Long operatorId, String reason);

    AiopsEventVO reject(Long eventId, Long operatorId, String reason);

    AiopsEventVO markExecuting(Long eventId, String traceId);

    AiopsEventVO markVerifying(Long eventId);

    AiopsEventVO markResolved(Long eventId, String resolutionType, String summary);

    AiopsEventVO markEscalated(Long eventId, String reason, String summary);

    AiopsMttrMetricsVO mttrMetrics(LocalDateTime from, LocalDateTime to, String source);
}
