package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.agent.AgentToolRegistry;
import org.example.online_used_marketplace.modules.ai.agent.harness.ToolExecutionHarness;
import org.example.online_used_marketplace.modules.ai.agent.harness.ToolExecutionResult;
import org.example.online_used_marketplace.modules.ai.agent.planner.AgentPlan;
import org.example.online_used_marketplace.modules.ai.agent.planner.AgentPlanStep;
import org.example.online_used_marketplace.modules.ai.agent.planner.AgentReplanDecision;
import org.example.online_used_marketplace.modules.ai.agent.planner.HybridAgentPlanner;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContext;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContextBuilder;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentExecutionIdentity;
import org.example.online_used_marketplace.modules.ai.agent.runtime.GraphExecutionObserver;
import org.example.online_used_marketplace.modules.ai.agent.runtime.GraphExecutionOptions;
import org.example.online_used_marketplace.modules.ai.dto.GraphAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.service.SessionSummaryService;
import org.example.online_used_marketplace.modules.ai.config.TokenGovernanceProperties;
import org.example.online_used_marketplace.modules.ai.service.TokenGovernanceService;
import org.example.online_used_marketplace.modules.ai.vo.AgentGraphNodeVO;
import org.example.online_used_marketplace.modules.ai.vo.GraphAgentResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphReactAgentReplanTest {

    @Test
    void shouldAppendReplanNodeAndGenerateFinalAnswerOnlyOnce() {
        AgentTool vectorTool = tool("vector_debug");
        AgentTool knowledgeTool = tool("knowledge_search");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(vectorTool, knowledgeTool));
        ToolExecutionHarness harness = mock(ToolExecutionHarness.class);
        ChatGenerationService generationService = mock(ChatGenerationService.class);
        SessionSummaryService summaryService = mock(SessionSummaryService.class);
        AgentRuntimeContextBuilder contextBuilder = mock(AgentRuntimeContextBuilder.class);
        HybridAgentPlanner planner = mock(HybridAgentPlanner.class);
        TokenGovernanceService tokenGovernanceService = new TokenGovernanceServiceImpl(
                new Cl100kTokenCounterService(), new TokenGovernanceProperties());

        AgentRuntimeContext context = new AgentRuntimeContext();
        context.setUserId("1");
        context.setSessionId("graph-replan");
        when(contextBuilder.build(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(context);
        when(contextBuilder.build(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenReturn(context);

        AgentPlan initial = new AgentPlan();
        initial.setPlannerType("RULE_FAST_PATH");
        initial.setReason("test");
        initial.setSteps(List.of(step("vector_debug", "vector_debug")));
        when(planner.plan(anyString(), any(), anyString(), anyString(), anyInt())).thenReturn(initial);
        when(planner.maxReplans()).thenReturn(2);

        AgentReplanDecision first = new AgentReplanDecision();
        first.setReplan(true);
        first.setRound(1);
        first.setReason("vector evidence is empty");
        first.setSteps(List.of(step("r1_knowledge_search", "knowledge_search")));
        when(planner.replan(anyString(), any(), anyString(), anyString(), any(), any(), anyInt(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(first);
        when(planner.replan(anyString(), any(), anyString(), anyString(), any(), any(), anyInt(), org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(AgentReplanDecision.stop(2, "sufficient"));

        when(harness.execute(any(), any(), anyString()))
                .thenReturn(execution("vector_debug", 0L, "no vector hits"))
                .thenReturn(execution("knowledge_search", 1L, "knowledge hit"));

        GraphReactAgentServiceImpl service = new GraphReactAgentServiceImpl(
                registry, harness, generationService, summaryService, contextBuilder, planner, tokenGovernanceService
        );
        List<List<String>> planSnapshots = new ArrayList<>();
        GraphExecutionObserver observer = new GraphExecutionObserver() {
            @Override
            public void onPlan(String traceId, List<AgentGraphNodeVO> nodes) {
                planSnapshots.add(nodes.stream().map(AgentGraphNodeVO::getNodeId).toList());
            }
        };
        GraphAgentRequestDTO request = new GraphAgentRequestDTO();
        request.setQuestion("检查向量召回问题");
        request.setSessionId("graph-replan");
        request.setTraceId("trace-replan");
        request.setMode("INTERNAL");
        request.setMaxSteps(5);

        GraphAgentResponseVO response = service.run(
                request, new GraphExecutionOptions(observer, Map.of(),
                        new AgentExecutionIdentity("1", "INTERNAL"))
        );

        assertThat(response.getGraph()).extracting(AgentGraphNodeVO::getNodeId)
                .contains("vector_debug", "r1_knowledge_search", "r1_observation_merge", "final_answer");
        assertThat(response.getGraph().stream().filter(node -> "FINAL".equals(node.getType()))).hasSize(1);
        assertThat(response.getDebugInfo()).contains("replanRounds=1");
        assertThat(planSnapshots).hasSize(3);
        verify(harness, times(2)).execute(any(), any(), anyString());
        verify(generationService, times(0)).generate(anyString());
    }

    private AgentPlanStep step(String id, String tool) {
        return new AgentPlanStep(id, tool, tool, Map.of(), List.of());
    }

    private ToolExecutionResult execution(String tool, long total, String summary) {
        ToolQueryResultVO observation = new ToolQueryResultVO();
        observation.setTool(tool);
        observation.setTotal(total);
        observation.setSummary(summary);
        return ToolExecutionResult.builder()
                .tool(tool)
                .status("SUCCESS")
                .observation(observation)
                .attempts(1)
                .build();
    }

    private AgentTool tool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn(name);
        when(tool.description()).thenReturn(name);
        when(tool.internalOnly()).thenReturn(true);
        return tool;
    }
}
