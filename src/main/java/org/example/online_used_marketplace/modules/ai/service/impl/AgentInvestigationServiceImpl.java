package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.dto.AgentInvestigationRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventCreateDTO;
import org.example.online_used_marketplace.modules.ai.dto.GraphAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ReactAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AgentInvestigationService;
import org.example.online_used_marketplace.modules.ai.service.AgentTracePersistenceService;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.service.GraphReactAgentService;
import org.example.online_used_marketplace.modules.ai.service.ReactAgentService;
import org.example.online_used_marketplace.modules.ai.vo.AgentInvestigationResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.GraphAgentResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ReactAgentResponseVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentInvestigationServiceImpl implements AgentInvestigationService {

    private final ReactAgentService reactAgentService;
    private final GraphReactAgentService graphReactAgentService;
    private final AgentTracePersistenceService tracePersistenceService;
    private final AiopsEventService aiopsEventService;

    @Override
    public AgentInvestigationResponseVO investigate(AgentInvestigationRequestDTO request) {
        long startedMs = System.currentTimeMillis();
        String traceId = StringUtils.hasText(request.getTraceId()) ? request.getTraceId().trim() : UUID.randomUUID().toString();
        RouteDecision decision = decide(request.getQuestion());
        try {
            return switch (decision.strategy()) {
                case "REACT" -> runReact(request, traceId, decision, startedMs);
                case "DAG", "DIRECT" -> runGraph(request, traceId, decision, startedMs);
                default -> throw new IllegalStateException("unsupported strategy: " + decision.strategy());
            };
        } catch (RuntimeException ex) {
            if ("REACT".equals(decision.strategy())) {
                tracePersistenceService.saveReactFailure(toReactRequest(request, traceId), ex, startedMs);
            } else {
                tracePersistenceService.saveGraphFailure(toGraphRequest(request, traceId), ex, startedMs);
            }
            throw ex;
        }
    }

    private AgentInvestigationResponseVO runGraph(AgentInvestigationRequestDTO request,
                                                   String traceId,
                                                   RouteDecision decision,
                                                   long startedMs) {
        GraphAgentRequestDTO graphRequest = toGraphRequest(request, traceId);
        GraphAgentResponseVO response = graphReactAgentService.run(graphRequest);
        tracePersistenceService.saveGraphTrace(graphRequest, response, startedMs);

        AgentInvestigationResponseVO out = base(response.getTraceId(), decision, response.getQuestion(),
                response.getAnswer(), response.getSuccess(), response.getElapsedMs());
        out.setAgentTrace(response.getAgentTrace());
        out.setObservations(response.getObservations());
        out.setSources(response.getSources());
        out.setGraph(response.getGraph());
        recordAiopsEventIfNeeded(request.getQuestion(), out);
        return out;
    }

    private AgentInvestigationResponseVO runReact(AgentInvestigationRequestDTO request,
                                                   String traceId,
                                                   RouteDecision decision,
                                                   long startedMs) {
        ReactAgentRequestDTO reactRequest = toReactRequest(request, traceId);
        ReactAgentResponseVO response = reactAgentService.run(reactRequest);
        tracePersistenceService.saveReactTrace(reactRequest, response, startedMs);

        AgentInvestigationResponseVO out = base(response.getTraceId(), decision, response.getQuestion(),
                response.getAnswer(), response.getSuccess(), null);
        out.setAgentTrace(response.getAgentTrace());
        out.setObservations(response.getObservations());
        out.setSources(response.getSources());
        recordAiopsEventIfNeeded(request.getQuestion(), out);
        return out;
    }

    private AgentInvestigationResponseVO base(String traceId,
                                              RouteDecision decision,
                                              String question,
                                              String answer,
                                              Boolean success,
                                              Long elapsedMs) {
        AgentInvestigationResponseVO out = new AgentInvestigationResponseVO();
        out.setTraceId(traceId);
        out.setStrategy(decision.strategy());
        out.setRouteReason(decision.reason());
        out.setQuestion(question);
        out.setAnswer(answer);
        out.setSuccess(Boolean.TRUE.equals(success));
        out.setStatus(Boolean.TRUE.equals(success) ? "COMPLETED" : "DEGRADED");
        out.setElapsedMs(elapsedMs);
        return out;
    }

    private GraphAgentRequestDTO toGraphRequest(AgentInvestigationRequestDTO request, String traceId) {
        GraphAgentRequestDTO out = new GraphAgentRequestDTO();
        out.setQuestion(request.getQuestion());
        out.setSessionId(request.getSessionId());
        out.setTraceId(traceId);
        out.setMaxSteps(request.getMaxSteps());
        out.setMode("INTERNAL");
        return out;
    }

    private ReactAgentRequestDTO toReactRequest(AgentInvestigationRequestDTO request, String traceId) {
        ReactAgentRequestDTO out = new ReactAgentRequestDTO();
        out.setQuestion(request.getQuestion());
        out.setSessionId(request.getSessionId());
        out.setTraceId(traceId);
        out.setMaxSteps(Math.min(request.getMaxSteps() == null ? 3 : request.getMaxSteps(), 5));
        out.setMode("INTERNAL");
        return out;
    }

    private RouteDecision decide(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        int independentReadIntents = count(
                containsAny(q, "prometheus", "p95", "p99", "指标", "监控", "告警", "延迟", "超时", "错误率"),
                containsAny(q, "日志", "报错", "error", "trace"),
                containsAny(q, "数据库", "mysql", "表", "索引任务", "chunk"),
                containsAny(q, "向量", "召回", "检索", "milvus", "知识库", "规则", "证据")
        );
        if (independentReadIntents >= 2) {
            return new RouteDecision("DAG", "识别到多个可独立执行的只读查询，使用 DAG 并行收集证据");
        }
        if (containsAny(q, "先", "再", "根据", "如果", "根因", "定位", "分析原因", "为什么会")) {
            return new RouteDecision("REACT", "后续查询需要依据前一步 Observation 动态决定，使用 ReAct 串行推理");
        }
        return new RouteDecision("DIRECT", "识别为单一确定性查询，使用单工具优先的直接执行路径");
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private int count(boolean... values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private void recordAiopsEventIfNeeded(String question, AgentInvestigationResponseVO response) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (!containsAny(q, "aiops", "prometheus", "p95", "p99", "latency", "timeout", "trace",
                "日志", "告警", "监控", "延迟", "超时", "错误率")) {
            return;
        }
        AiopsEventCreateDTO event = new AiopsEventCreateDTO();
        event.setEventKey("agent-" + response.getTraceId());
        event.setTraceId(response.getTraceId());
        event.setSeverity("INFO");
        event.setSource("agent_investigate");
        event.setTitle("智能排查诊断事件");
        event.setSummary(response.getRouteReason());
        event.setSuggestion(response.getAnswer());
        event.setMetadata(Map.of("strategy", response.getStrategy(), "success", response.getSuccess()));
        aiopsEventService.createOrUpdate(event);
    }

    private record RouteDecision(String strategy, String reason) {
    }
}
