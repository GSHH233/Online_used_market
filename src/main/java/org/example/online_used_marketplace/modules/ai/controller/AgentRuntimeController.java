package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentExecutionIdentity;
import org.example.online_used_marketplace.modules.ai.dto.AgentRuntimeStartRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AgentRuntimeService;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeCheckpointVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeAccessAuditVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeRunVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/agent/runs")
public class AgentRuntimeController {

    private final AgentRuntimeService agentRuntimeService;

    @PostMapping
    public Result<AgentRuntimeRunVO> start(@Valid @RequestBody AgentRuntimeStartRequestDTO request) {
        ToolAccessGuard.requireInternalEndpoint("runtime_start");
        return Result.success(agentRuntimeService.start(request));
    }

    @GetMapping
    public Result<List<AgentRuntimeRunVO>> list(@RequestParam(defaultValue = "20") Integer limit) {
        ToolAccessGuard.requireInternalEndpoint("runtime_list");
        AgentExecutionIdentity identity = AgentExecutionIdentity.current();
        int requested = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        List<AgentRuntimeRunVO> visible = agentRuntimeService.list(identity.isAdmin() ? requested : 100).stream()
                .filter(run -> identity.isAdmin() || identity.userId().equals(run.getOwnerUserId()))
                .limit(requested)
                .toList();
        return Result.success(visible);
    }

    @GetMapping("/{runId}")
    public Result<AgentRuntimeRunVO> get(@PathVariable String runId) {
        ToolAccessGuard.requireInternalEndpoint("runtime_detail");
        return Result.success(requireVisible(runId));
    }

    @PostMapping("/{runId}/pause")
    public Result<AgentRuntimeRunVO> pause(@PathVariable String runId) {
        ToolAccessGuard.requireAdminEndpoint("runtime_pause");
        return Result.success(agentRuntimeService.pause(runId));
    }

    @PostMapping("/{runId}/resume")
    public Result<AgentRuntimeRunVO> resume(@PathVariable String runId) {
        ToolAccessGuard.requireAdminEndpoint("runtime_resume");
        return Result.success(agentRuntimeService.resume(runId));
    }

    @PostMapping("/{runId}/cancel")
    public Result<AgentRuntimeRunVO> cancel(@PathVariable String runId) {
        ToolAccessGuard.requireAdminEndpoint("runtime_cancel");
        return Result.success(agentRuntimeService.cancel(runId));
    }

    @PostMapping("/{runId}/replay")
    public Result<AgentRuntimeRunVO> replay(
            @PathVariable String runId,
            @RequestParam(defaultValue = "false") boolean liveReadonly) {
        ToolAccessGuard.requireAdminEndpoint("runtime_replay");
        return Result.success(agentRuntimeService.replay(runId, liveReadonly));
    }

    @GetMapping("/{runId}/checkpoints")
    public Result<List<AgentRuntimeCheckpointVO>> checkpoints(@PathVariable String runId) {
        ToolAccessGuard.requireInternalEndpoint("runtime_checkpoints");
        requireVisible(runId);
        return Result.success(agentRuntimeService.checkpoints(runId));
    }

    @GetMapping("/{runId}/access-audits")
    public Result<List<AgentRuntimeAccessAuditVO>> accessAudits(
            @PathVariable String runId,
            @RequestParam(defaultValue = "50") Integer limit) {
        ToolAccessGuard.requireAdminEndpoint("runtime_access_audits");
        agentRuntimeService.get(runId);
        return Result.success(agentRuntimeService.accessAudits(runId, limit));
    }

    private AgentRuntimeRunVO requireVisible(String runId) {
        AgentRuntimeRunVO run = agentRuntimeService.get(runId);
        AgentExecutionIdentity identity = AgentExecutionIdentity.current();
        if (!identity.isAdmin() && !identity.userId().equals(run.getOwnerUserId())) {
            agentRuntimeService.recordAccessAudit(
                    runId, "READ_DETAIL", "DENY", "actor is not the Runtime owner");
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问其他用户的 Runtime Run");
        }
        return run;
    }
}
