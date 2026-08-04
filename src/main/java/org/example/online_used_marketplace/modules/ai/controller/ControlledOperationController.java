package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.AiopsApprovalRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ControlledOperationPlanRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.ControlledOperationService;
import org.example.online_used_marketplace.modules.ai.vo.ControlledOperationVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/operations")
public class ControlledOperationController {

    private final ControlledOperationService controlledOperationService;

    @PostMapping("/plans")
    public Result<ControlledOperationVO> create(@Valid @RequestBody ControlledOperationPlanRequestDTO request) {
        ToolAccessGuard.requireInternalEndpoint("controlled_operation_plan");
        return Result.success(controlledOperationService.createPlan(request));
    }

    @GetMapping
    public Result<List<ControlledOperationVO>> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") Integer limit) {
        ToolAccessGuard.requireInternalEndpoint("controlled_operation_list");
        return Result.success(controlledOperationService.list(status, limit));
    }

    @GetMapping("/{operationId}")
    public Result<ControlledOperationVO> get(@PathVariable Long operationId) {
        ToolAccessGuard.requireInternalEndpoint("controlled_operation_detail");
        return Result.success(controlledOperationService.get(operationId));
    }

    @PostMapping("/{operationId}/approve")
    public Result<ControlledOperationVO> approve(@PathVariable Long operationId,
                                                  @RequestBody(required = false) AiopsApprovalRequestDTO request) {
        Long operatorId = ToolAccessGuard.requireAdminUserId("controlled_operation_approve");
        return Result.success(controlledOperationService.approve(operationId, operatorId, request == null ? null : request.getReason()));
    }

    @PostMapping("/{operationId}/reject")
    public Result<ControlledOperationVO> reject(@PathVariable Long operationId,
                                                 @RequestBody(required = false) AiopsApprovalRequestDTO request) {
        Long operatorId = ToolAccessGuard.requireAdminUserId("controlled_operation_reject");
        return Result.success(controlledOperationService.reject(operationId, operatorId, request == null ? null : request.getReason()));
    }

    @PostMapping("/{operationId}/execute")
    public Result<ControlledOperationVO> execute(@PathVariable Long operationId) {
        ToolAccessGuard.requireAdminEndpoint("controlled_operation_execute");
        return Result.success(controlledOperationService.execute(operationId));
    }
}
