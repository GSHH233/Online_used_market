package org.example.online_used_marketplace.modules.ai.controller;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventCreateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventUpdateDTO;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;
import org.springframework.web.bind.annotation.*;

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
}
