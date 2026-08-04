package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.AgentRuntimeStartRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AgentRuntimeService;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeRunVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeCheckpointVO;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/agent/runs")
public class AgentRuntimeController {
    private final AgentRuntimeService agentRuntimeService;
    @PostMapping public Result<AgentRuntimeRunVO> start(@Valid @RequestBody AgentRuntimeStartRequestDTO request) { ToolAccessGuard.requireInternalEndpoint("runtime_start"); return Result.success(agentRuntimeService.start(request)); }
    @GetMapping public Result<List<AgentRuntimeRunVO>> list(@RequestParam(defaultValue = "20") Integer limit) { ToolAccessGuard.requireInternalEndpoint("runtime_list"); return Result.success(agentRuntimeService.list(limit)); }
    @GetMapping("/{runId}") public Result<AgentRuntimeRunVO> get(@PathVariable String runId) { ToolAccessGuard.requireInternalEndpoint("runtime_detail"); return Result.success(agentRuntimeService.get(runId)); }
    @PostMapping("/{runId}/pause") public Result<AgentRuntimeRunVO> pause(@PathVariable String runId) { ToolAccessGuard.requireAdminEndpoint("runtime_pause"); return Result.success(agentRuntimeService.pause(runId)); }
    @PostMapping("/{runId}/resume") public Result<AgentRuntimeRunVO> resume(@PathVariable String runId) { ToolAccessGuard.requireAdminEndpoint("runtime_resume"); return Result.success(agentRuntimeService.resume(runId)); }
    @PostMapping("/{runId}/cancel") public Result<AgentRuntimeRunVO> cancel(@PathVariable String runId) { ToolAccessGuard.requireAdminEndpoint("runtime_cancel"); return Result.success(agentRuntimeService.cancel(runId)); }
    @PostMapping("/{runId}/replay") public Result<AgentRuntimeRunVO> replay(@PathVariable String runId, @RequestParam(defaultValue = "false") boolean liveReadonly) { ToolAccessGuard.requireAdminEndpoint("runtime_replay"); return Result.success(agentRuntimeService.replay(runId, liveReadonly)); }
    @GetMapping("/{runId}/checkpoints") public Result<List<AgentRuntimeCheckpointVO>> checkpoints(@PathVariable String runId) { ToolAccessGuard.requireInternalEndpoint("runtime_checkpoints"); return Result.success(agentRuntimeService.checkpoints(runId)); }
}
