package org.example.online_used_marketplace.modules.ai.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.agent.AgentToolRegistry;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContext;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HybridAgentPlannerTest {

    private AgentPlanValidator validator;
    private HybridAgentPlanner planner;
    private AgentRuntimeContext runtimeContext;

    @BeforeEach
    void setUp() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("knowledge_search", false),
                tool("profile", false),
                tool("prometheus_query", true),
                tool("log_query", true),
                tool("mysql_read", true),
                tool("vector_debug", true),
                tool("harness_fault", true)
        ));
        validator = new AgentPlanValidator(registry);
        planner = new HybridAgentPlanner(registry, validator, mock(ChatGenerationService.class), new ObjectMapper());
        ReflectionTestUtils.setField(planner, "llmEnabled", false);
        ReflectionTestUtils.setField(planner, "configuredMaxReplans", 2);

        runtimeContext = new AgentRuntimeContext();
        runtimeContext.setUserId("42");
        runtimeContext.setSessionId("session-1");
    }

    @Test
    void shouldUseRuleFastPathAndKeepExplicitUserDenialStep() {
        AgentPlan plan = planner.plan(
                "帮我查数据库里最近的知识文档", runtimeContext, "trace-1", "USER", 5
        );

        assertThat(plan.getPlannerType()).isEqualTo("RULE_FAST_PATH");
        assertThat(plan.getSteps()).extracting(AgentPlanStep::getTool).containsExactly("mysql_read");
        assertThat(plan.getValidationWarnings()).anyMatch(value -> value.contains("will be denied"));
    }

    @Test
    void shouldReplaceModelOwnedSensitiveInputsWithServerOwnedValues() {
        AgentPlanStep unsafe = new AgentPlanStep(
                "sql", "mysql_read", "read data",
                Map.of("sql", "delete from user", "limit", 9999), List.of()
        );

        AgentPlanValidator.ValidationResult result = validator.validate(
                List.of(unsafe), "检查知识文档", runtimeContext,
                "trace-safe", "INTERNAL", 3, ""
        );

        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).getInput().get("sql"))
                .isEqualTo("select id, title, status, created_time from ai_knowledge_doc order by id desc");
        assertThat(result.steps().get(0).getInput().get("limit")).isEqualTo(20);
    }

    @Test
    void shouldAddVectorDebugWhenKnowledgeEvidenceIsEmpty() {
        ToolQueryResultVO emptyKnowledge = observation("knowledge_search", 0L, "no hits");

        AgentReplanDecision decision = planner.replan(
                "为什么知识库没有召回", runtimeContext, "trace-2", "INTERNAL",
                List.of(emptyKnowledge), Set.of("knowledge_search"), 2, 1
        );

        assertThat(decision.isReplan()).isTrue();
        assertThat(decision.getSteps()).extracting(AgentPlanStep::getId)
                .containsExactly("r1_vector_debug");
        assertThat(decision.getSteps()).extracting(AgentPlanStep::getTool)
                .containsExactly("vector_debug");
    }

    @Test
    void shouldStopAfterConfiguredReplanLimit() {
        AgentReplanDecision decision = planner.replan(
                "为什么知识库没有召回", runtimeContext, "trace-3", "INTERNAL",
                List.of(observation("knowledge_search", 0L, "no hits")),
                Set.of("knowledge_search"), 2, 3
        );

        assertThat(decision.isReplan()).isFalse();
        assertThat(decision.getReason()).contains("limit");
    }

    @Test
    void shouldNotExpandPrivilegesAfterDeniedObservation() {
        ToolQueryResultVO denied = observation("prometheus_query", 0L, "拒绝：USER模式不允许调用内部工具");
        denied.setTarget("denied");

        AgentReplanDecision decision = planner.replan(
                "查看 Prometheus 指标", runtimeContext, "trace-4", "USER",
                List.of(denied), Set.of("prometheus_query"), 3, 1
        );

        assertThat(decision.isReplan()).isFalse();
        assertThat(decision.getSteps()).isEmpty();
    }

    @Test
    void shouldFallbackToRulePlanWhenStructuredPlannerFails() {
        ReflectionTestUtils.setField(planner, "llmEnabled", true);

        AgentPlan plan = planner.plan(
                "先查 P95 指标，再查日志定位根因", runtimeContext,
                "trace-5", "INTERNAL", 5
        );

        assertThat(plan.getPlannerType()).isEqualTo("RULE_FALLBACK");
        assertThat(plan.isFallback()).isTrue();
        assertThat(plan.getSteps()).extracting(AgentPlanStep::getTool)
                .contains("prometheus_query", "log_query");
        assertThat(plan.getValidationWarnings()).anyMatch(value -> value.startsWith("llmPlannerError="));
    }

    private ToolQueryResultVO observation(String tool, long total, String summary) {
        ToolQueryResultVO result = new ToolQueryResultVO();
        result.setTool(tool);
        result.setTotal(total);
        result.setSummary(summary);
        return result;
    }

    private AgentTool tool(String name, boolean internalOnly) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public boolean internalOnly() {
                return internalOnly;
            }

            @Override
            public ToolQueryResultVO execute(Map<String, Object> input) {
                return new ToolQueryResultVO();
            }
        };
    }
}
