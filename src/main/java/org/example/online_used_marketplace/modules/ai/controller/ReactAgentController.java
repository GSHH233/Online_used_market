package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventCreateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AgentInvestigationRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.AgentTraceQueryDTO;
import org.example.online_used_marketplace.modules.ai.dto.GraphAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ReactAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AgentTracePersistenceService;
import org.example.online_used_marketplace.modules.ai.service.AgentInvestigationService;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.service.EvalDashboardService;
import org.example.online_used_marketplace.modules.ai.service.GraphReactAgentService;
import org.example.online_used_marketplace.modules.ai.service.ReactAgentService;
import org.example.online_used_marketplace.modules.ai.vo.AgentTraceRunVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentInvestigationResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.EvalDashboardVO;
import org.example.online_used_marketplace.modules.ai.vo.GraphAgentResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ReactAgentResponseVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/agent")
public class ReactAgentController {

    private final ReactAgentService reactAgentService;
    private final GraphReactAgentService graphReactAgentService;
    private final AgentInvestigationService agentInvestigationService;
    private final AgentTracePersistenceService tracePersistenceService;
    private final EvalDashboardService evalDashboardService;
    private final AiopsEventService aiopsEventService;

    @PostMapping("/react-chat")
    public Result<ReactAgentResponseVO> reactChat(@Valid @RequestBody ReactAgentRequestDTO request) {
        ToolAccessGuard.validateInternalModeIfRequested(request.getMode(), "react_agent");
        long startedMs = System.currentTimeMillis();
        try {
            ReactAgentResponseVO response = reactAgentService.run(request);
            tracePersistenceService.saveReactTrace(request, response, startedMs);
            return Result.success(response);
        } catch (RuntimeException ex) {
            tracePersistenceService.saveReactFailure(request, ex, startedMs);
            throw ex;
        }
    }

    @PostMapping("/investigate")
    public Result<AgentInvestigationResponseVO> investigate(@Valid @RequestBody AgentInvestigationRequestDTO request) {
        ToolAccessGuard.requireInternalEndpoint("agent_investigate");
        return Result.success(agentInvestigationService.investigate(request));
    }

    @PostMapping("/graph-chat")
    public Result<GraphAgentResponseVO> graphChat(@Valid @RequestBody GraphAgentRequestDTO request) {
        ToolAccessGuard.validateInternalModeIfRequested(request.getMode(), "graph_agent");
        long startedMs = System.currentTimeMillis();
        try {
            GraphAgentResponseVO response = graphReactAgentService.run(request);
            tracePersistenceService.saveGraphTrace(request, response, startedMs);
            recordAiopsEventIfNeeded(request, response);
            return Result.success(response);
        } catch (RuntimeException ex) {
            tracePersistenceService.saveGraphFailure(request, ex, startedMs);
            throw ex;
        }
    }

    @GetMapping("/traces")
    public Result<List<AgentTraceRunVO>> listTraces(AgentTraceQueryDTO query) {
        ToolAccessGuard.requireInternalEndpoint("agent_trace_list");
        return Result.success(tracePersistenceService.list(query));
    }

    @GetMapping("/traces/{traceId}")
    public Result<AgentTraceRunVO> getTrace(@PathVariable String traceId) {
        ToolAccessGuard.requireInternalEndpoint("agent_trace_detail");
        return Result.success(tracePersistenceService.getByTraceId(traceId));
    }

    @GetMapping("/eval-dashboard")
    public Result<EvalDashboardVO> evalDashboard(@RequestParam(defaultValue = "20") Integer recentLimit) {
        ToolAccessGuard.requireInternalEndpoint("eval_dashboard");
        return Result.success(evalDashboardService.dashboard(recentLimit));
    }

    private void recordAiopsEventIfNeeded(GraphAgentRequestDTO request, GraphAgentResponseVO response) {
        if (request == null || response == null || !isAiopsQuestion(request.getQuestion())) {
            return;
        }
        AiopsEventCreateDTO dto = new AiopsEventCreateDTO();
        dto.setEventKey("agent-" + response.getTraceId());
        dto.setTraceId(response.getTraceId());
        dto.setSeverity("INFO");
        dto.setSource("graph_agent");
        dto.setTitle("Graph Agent AIOps诊断事件");
        dto.setSummary(response.getDebugInfo());
        dto.setSuggestion(response.getAnswer());
        aiopsEventService.createOrUpdate(dto);
    }

    private boolean isAiopsQuestion(String question) {
        if (question == null) {
            return false;
        }
        String q = question.toLowerCase();
        return q.contains("aiops") || q.contains("prometheus") || q.contains("p95")
                || q.contains("p99") || q.contains("latency") || q.contains("timeout")
                || q.contains("trace") || q.contains("日志") || q.contains("告警")
                || q.contains("监控") || q.contains("延迟") || q.contains("超时");
    }
}
