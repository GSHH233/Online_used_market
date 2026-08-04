package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.ControlledOperationPlanRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ControlledOperationVO;

import java.util.List;

public interface ControlledOperationService {

    ControlledOperationVO createPlan(ControlledOperationPlanRequestDTO request);

    List<ControlledOperationVO> list(String status, Integer limit);

    ControlledOperationVO get(Long operationId);

    ControlledOperationVO approve(Long operationId, Long operatorId, String reason);

    ControlledOperationVO reject(Long operationId, Long operatorId, String reason);

    ControlledOperationVO execute(Long operationId);
}
