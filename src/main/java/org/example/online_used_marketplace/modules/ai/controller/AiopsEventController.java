package org.example.online_used_marketplace.modules.ai.controller;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventCreateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventUpdateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsApprovalRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsMttrMetricsVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/aiops/events")
public class AiopsEventController {

    private final AiopsEventService aiopsEventService;

    @PostMapping
    public Result<AiopsEventVO> create(@RequestBody AiopsEventCreateDTO dto) {
        ToolAccessGuard.requireInternalEndpoint("aiops_event_create");
        return Result.success(aiopsEventService.createOrUpdate(dto));
    }

    @GetMapping
    public Result<List<AiopsEventVO>> list(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String severity,
                                           @RequestParam(defaultValue = "20") Integer limit) {
        ToolAccessGuard.requireInternalEndpoint("aiops_event_list");
        return Result.success(aiopsEventService.list(status, severity, limit));
    }

    @GetMapping("/{eventId}")
    public Result<AiopsEventVO> get(@PathVariable Long eventId) {
        ToolAccessGuard.requireInternalEndpoint("aiops_event_detail");
        return Result.success(aiopsEventService.get(eventId));
    }

    @PatchMapping("/{eventId}")
    public Result<AiopsEventVO> update(@PathVariable Long eventId,
                                       @RequestBody AiopsEventUpdateDTO dto) {
        ToolAccessGuard.requireInternalEndpoint("aiops_event_update");
        return Result.success(aiopsEventService.update(eventId, dto));
    }

    @PostMapping("/{eventId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<AiopsEventVO> approve(@PathVariable Long eventId,
                                        @RequestBody(required = false) AiopsApprovalRequestDTO dto) {
        Long operatorId = ToolAccessGuard.requireAdminUserId("aiops_event_approve");
        return Result.success(aiopsEventService.approve(eventId, operatorId, dto == null ? null : dto.getReason()));
    }

    @PostMapping("/{eventId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<AiopsEventVO> reject(@PathVariable Long eventId,
                                       @RequestBody(required = false) AiopsApprovalRequestDTO dto) {
        Long operatorId = ToolAccessGuard.requireAdminUserId("aiops_event_reject");
        return Result.success(aiopsEventService.reject(eventId, operatorId, dto == null ? null : dto.getReason()));
    }

    @GetMapping("/{eventId}/remediation")
    public Result<AiopsEventVO> remediation(@PathVariable Long eventId) {
        ToolAccessGuard.requireInternalEndpoint("aiops_event_remediation");
        return Result.success(aiopsEventService.get(eventId));
    }

    @GetMapping("/metrics/mttr")
    public Result<AiopsMttrMetricsVO> mttr(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String source) {
        ToolAccessGuard.requireInternalEndpoint("aiops_mttr_metrics");
        return Result.success(aiopsEventService.mttrMetrics(from, to, source));
    }
}
