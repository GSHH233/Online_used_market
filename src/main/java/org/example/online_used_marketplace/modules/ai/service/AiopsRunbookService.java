package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.AiopsRunbookExecuteRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsRunbookActionVO;

import java.util.List;

public interface AiopsRunbookService {

    List<AiopsRunbookActionVO> listRunbooks();

    AiopsRunbookActionVO execute(AiopsRunbookExecuteRequestDTO request);
}
