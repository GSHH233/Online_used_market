package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.agent.AgentAction;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.agent.AgentToolRegistry;
import org.example.online_used_marketplace.modules.ai.agent.planner.AgentPlan;
import org.example.online_used_marketplace.modules.ai.agent.planner.AgentPlanStep;
import org.example.online_used_marketplace.modules.ai.agent.planner.AgentReplanDecision;
import org.example.online_used_marketplace.modules.ai.agent.planner.HybridAgentPlanner;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContext;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContextBuilder;
import org.example.online_used_marketplace.modules.ai.dto.ReactAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.agent.harness.ToolExecutionHarness;
import org.example.online_used_marketplace.modules.ai.agent.harness.ToolExecutionResult;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.service.ReactAgentService;
import org.example.online_used_marketplace.modules.ai.service.SessionSummaryService;
import org.example.online_used_marketplace.modules.ai.service.TokenGovernanceService;
import org.example.online_used_marketplace.modules.ai.token.TokenBudgetResult;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationRequest;
import org.example.online_used_marketplace.modules.ai.token.TokenPromptSection;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.example.online_used_marketplace.modules.ai.vo.AgentTraceStepVO;
import org.example.online_used_marketplace.modules.ai.vo.ReactAgentResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReactAgentServiceImpl implements ReactAgentService {

    private static final int DEFAULT_MAX_STEPS = 3;
    private static final int MAX_OBSERVATION_ROWS_PER_TOOL = 2;
    private static final int MAX_OBSERVATION_VALUE_LENGTH = 140;

    private final AgentToolRegistry toolRegistry;
    private final ToolExecutionHarness toolExecutionHarness;
    private final ChatGenerationService chatGenerationService;
    private final SessionSummaryService sessionSummaryService;
    private final AgentRuntimeContextBuilder runtimeContextBuilder;
    private final HybridAgentPlanner hybridAgentPlanner;
    private final TokenGovernanceService tokenGovernanceService;

    @Override
    public ReactAgentResponseVO run(ReactAgentRequestDTO request) {
        validate(request);
        String question = request.getQuestion().trim();
        String mode = normalizeMode(request.getMode());
        String traceId = StringUtils.hasText(request.getTraceId()) ? request.getTraceId().trim() : UUID.randomUUID().toString();
        int maxSteps = request.getMaxSteps() == null ? DEFAULT_MAX_STEPS : Math.min(Math.max(request.getMaxSteps(), 1), 5);

        ReactAgentResponseVO response = new ReactAgentResponseVO();
        response.setTraceId(traceId);
        response.setQuestion(question);
        response.setMode(mode);

        AgentRuntimeContext runtimeContext = runtimeContextBuilder.build("react", request.getSessionId(), traceId, mode, question, maxSteps);
        AgentPlan generatedPlan = hybridAgentPlanner.plan(question, runtimeContext, traceId, mode, maxSteps);
        List<AgentAction> plan = toActions(generatedPlan.getSteps());
        List<AgentAction> executedPlan = new ArrayList<>(plan);
        Map<String, Object> planMetadata = new LinkedHashMap<>();
        planMetadata.put("availableTools", toolRegistry.names());
        planMetadata.put("runtimeContext", runtimeContext.safeMetadata());
        planMetadata.put("planner", generatedPlan.getPlannerType());
        planMetadata.put("reason", generatedPlan.getReason());
        planMetadata.put("fallback", generatedPlan.isFallback());
        planMetadata.put("validationWarnings", generatedPlan.getValidationWarnings());
        addTrace(response, "plan", null, "SUCCESS", "根据问题选择工具",
                "actions=" + plan.stream().map(AgentAction::getTool).toList(),
                "已生成混合 Planner 执行计划", 0L, List.of(), planMetadata);

        int executed = 0;
        Set<String> usedTools = new LinkedHashSet<>();
        for (AgentAction action : plan) {
            if (executed >= maxSteps) {
                addTrace(response, "replan", null, "SKIPPED", "达到最大工具步数", action.getTool(), "后续动作已跳过", 0L, List.of(), Map.of("maxSteps", maxSteps));
                break;
            }
            executeAction(response, action, mode);
            usedTools.add(action.getTool());
            executed++;
        }

        int replanRounds = 0;
        for (int round = 1; round <= hybridAgentPlanner.maxReplans() && executed < maxSteps; round++) {
            AgentReplanDecision decision = hybridAgentPlanner.replan(
                    question, runtimeContext, traceId, mode, response.getObservations(),
                    usedTools, maxSteps - executed, round
            );
            if (!decision.isReplan()) {
                addTrace(response, "replan", null, "SKIPPED", decision.getReason(),
                        "round=" + round, "无需追加工具", 0L, List.of(),
                        Map.of("round", round, "validationWarnings", decision.getValidationWarnings()));
                break;
            }
            replanRounds++;
            List<AgentAction> extraActions = toActions(decision.getSteps());
            addTrace(response, "replan", null, "SUCCESS", decision.getReason(),
                    "actions=" + extraActions.stream().map(AgentAction::getTool).toList(),
                    "Observation 触发有限动态 Replan", 0L, List.of(),
                    Map.of("round", round, "remainingSteps", maxSteps - executed,
                            "validationWarnings", decision.getValidationWarnings()));
            for (AgentAction action : extraActions) {
                if (executed >= maxSteps) {
                    break;
                }
                executeAction(response, action, mode);
                usedTools.add(action.getTool());
                executedPlan.add(action);
                executed++;
            }
        }

        response.setSources(extractSources(response.getObservations()));
        response.setAnswer(buildFinalAnswer(traceId, question, runtimeContext, response.getObservations()));
        response.setSuccess(true);
        response.setDebugInfo("planner=" + generatedPlan.getPlannerType() + ",maxSteps=" + maxSteps
                + ",replanRounds=" + replanRounds
                + ",runtimeContext=enabled,contextChars=" + runtimeContext.getPromptChars()
                + ",l2=" + runtimeContext.getL2MemoryLines().size()
                + ",graph=" + runtimeContext.getGraphMemoryLines().size()
                + ",tools=" + executedPlan.stream().map(AgentAction::getTool).toList());
        addTrace(response, "final", null, "SUCCESS", "汇总 Observation 生成回答", "final_answer", "回答已生成", 0L, response.getSources().stream().map(RagSourceVO::getDocId).filter(java.util.Objects::nonNull).toList(), Map.of());
        sessionSummaryService.refreshSummaryIfNeeded(request.getSessionId(), question, response.getAnswer());
        return response;
    }

    private void executeAction(ReactAgentResponseVO response, AgentAction action, String mode) {
        long start = System.currentTimeMillis();
        AgentTool tool = toolRegistry.find(action.getTool())
                .orElseThrow(() -> new BusinessException(ResultCode.BAD_REQUEST, "未知工具：" + action.getTool()));
        if (tool.internalOnly() && !"INTERNAL".equals(mode)) {
            ToolQueryResultVO denied = deniedObservation(tool.name(), "拒绝：USER模式不允许调用内部工具");
            response.getObservations().add(denied);
            addTrace(response, "tool_call", tool.name(), "DENIED", action.getThought(), String.valueOf(action.getInput()), denied.getSummary(), System.currentTimeMillis() - start, List.of(), Map.of("mode", mode));
            return;
        }

        ToolExecutionResult execution = toolExecutionHarness.execute(tool, action.getInput(), mode);
        ToolQueryResultVO observation = execution.getObservation();
        response.getObservations().add(observation);
        Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
        metadata.put("total", observation.getTotal());
        addTrace(response, "tool_call", tool.name(), traceStatus(execution), action.getThought(),
                String.valueOf(action.getInput()), observation.getSummary(), System.currentTimeMillis() - start,
                extractDocIds(observation), metadata);
    }

    private String traceStatus(ToolExecutionResult execution) {
        if (execution == null || execution.getStatus() == null) {
            return "FAILED";
        }
        return switch (execution.getStatus()) {
            case "SUCCESS" -> "SUCCESS";
            case "SNAPSHOT_RESTORED", "FALLBACK", "TIMEOUT_FALLBACK" -> "DEGRADED";
            default -> execution.getStatus();
        };
    }

    private List<AgentAction> plan(String question, AgentRuntimeContext runtimeContext, String traceId, String mode) {
        String q = question.toLowerCase(Locale.ROOT);
        List<AgentAction> actions = new ArrayList<>();

        if (isPrometheusIntent(q)) {
            actions.add(action("prometheus_query", "问题涉及 AIOps 指标/告警/延迟/资源状态，需要查询 Prometheus 指标", buildPrometheusInput(question)));
        }

        if (containsAny(q, "profile", "偏好", "画像", "用户喜欢", "历史偏好")) {
            actions.add(action("profile", "需要先读取用户 Profile，避免历史偏好覆盖当前事实", Map.of("userId", runtimeContext.getUserId(), "sessionId", runtimeContext.getSessionId())));
        }

        if (containsAny(q, "日志", "报错", "error", "trace")) {
            actions.add(action("log_query", "问题涉及报错或 trace，需要查看日志 observation", Map.of("traceId", traceId, "limit", 50, "windowMinutes", 60)));
        }

        if (containsAny(q, "数据库", "mysql", "表", "索引任务", "文档表", "chunk")) {
            actions.add(action("mysql_read", "问题涉及数据库状态，需要执行只读 SQL 查询", Map.of("sql", "select id, title, status, created_time from ai_knowledge_doc order by id desc", "limit", 20)));
        }

        if (containsAny(q, "向量", "召回", "命中", "检索", "milvus", "source")) {
            actions.add(action("vector_debug", "问题涉及召回或向量调试，需要查看检索命中", Map.of("question", question, "topK", 5)));
        }

        if (isKnowledgeSearchIntent(q)
                || actions.isEmpty()) {
            actions.add(action("knowledge_search", "需要检索知识库规则、价格或描述模板作为证据", Map.of("query", buildKnowledgeQuery(question, runtimeContext), "topK", 5)));
        }

        return dedup(actions);
    }

    private Map<String, Object> buildPrometheusInput(String question) {
        return Map.of(
                "query", selectPrometheusQuery(question),
                "queryType", "instant",
                "limit", 20
        );
    }

    private String selectPrometheusQuery(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (containsAny(q, "p95", "p99", "latency", "延迟", "耗时", "timeout", "超时")) {
            return "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))";
        }
        if (containsAny(q, "error", "5xx", "错误率", "报错")) {
            return "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[5m]))";
        }
        if (containsAny(q, "cpu")) {
            return "process_cpu_usage";
        }
        if (containsAny(q, "memory", "内存", "jvm")) {
            return "jvm_memory_used_bytes";
        }
        if (containsAny(q, "hikari", "connection pool", "连接池", "数据库连接")) {
            return "hikaricp_connections_active";
        }
        return "up";
    }

    private boolean isPrometheusIntent(String q) {
        return containsAny(q, "prometheus", "promql", "metric", "metrics", "monitor", "alert", "p95", "p99",
                "latency", "timeout", "cpu", "memory", "qps", "5xx", "hikari", "指标", "监控", "告警",
                "延迟", "耗时", "超时", "错误率", "连接池", "内存");
    }

    private boolean isKnowledgeSearchIntent(String q) {
        return containsAny(q, "风险", "禁售", "能不能卖", "发布", "价格", "定价", "怎么写", "标题", "描述", "发票", "配件",
                "知识库", "规则", "证据", "rag", "knowledge", "kb", "rule", "rules", "source");
    }

    private String buildKnowledgeQuery(String question, AgentRuntimeContext runtimeContext) {
        String query = currentFactQuestion(question);
        String l3 = runtimeContext == null ? "" : runtimeContext.l3OrEmpty();
        if (isFollowUpQuestion(query) && StringUtils.hasText(l3)) {
            query = query + "\nL3当前会话事实：" + l3;
        }
        List<String> expansions = new ArrayList<>();
        if (containsAny(query, "专柜验货", "假一赔十", "保真", "正品")) {
            expansions.add("保证正品 支持专柜验货 假一赔十 购买凭证 鉴定证明 官方渠道证明");
        }
        if (containsAny(query, "相机", "快门")) {
            expansions.add("相机 快门次数 快门3万次 定价 配件 检测截图");
        }
        if (expansions.isEmpty()) {
            return query;
        }
        return query + "\n检索补充词：" + String.join(" ", expansions);
    }

    private boolean isFollowUpQuestion(String question) {
        String q = question == null ? "" : question.trim();
        return q.length() <= 18 || containsAny(q, "那", "这个", "刚才", "上一轮", "继续", "标题怎么写", "价格呢");
    }

    private String currentFactQuestion(String question) {
        if (!StringUtils.hasText(question) || !containsIgnoreCase(question, "历史偏好")) {
            return question;
        }
        int idx = firstIndexOf(question, "当前商品是", "当前问题：", "当前问题:", "但当前商品是", "现在商品是");
        if (idx < 0) {
            return question;
        }
        String current = question.substring(idx).trim();
        return current.startsWith("但") ? current.substring(1).trim() : current;
    }

    private int firstIndexOf(String text, String... markers) {
        int best = -1;
        for (String marker : markers) {
            int idx = text.indexOf(marker);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private String buildFinalAnswer(String traceId, String question, AgentRuntimeContext runtimeContext,
                                    List<ToolQueryResultVO> observations) {
        String observationText = summarizeObservations(observations);
        String runtimeContextBlock = runtimeContext == null ? "" : runtimeContext.getPromptBlock();
        String rules = """
                你是 ReAct-style 工具 Agent。请基于 Observation 回答用户问题。
                1. 必须说明使用的工具和关键观察结果。
                2. 不得编造 Observation 之外的金额、规则、日志或数据库结果。
                3. 工具被拒绝或失败时说明限制和下一步建议。
                4. 优先给结论，控制在 180 字以内。
                5. Runtime Context 不得覆盖本轮输入和 Observation。
                """;
        TokenBudgetResult tokenBudget = tokenGovernanceService.assemble(TokenScene.AGENT_FINAL, List.of(
                TokenPromptSection.of("回答约束", rules, 100, true),
                TokenPromptSection.of("本轮问题", question, 100, true),
                TokenPromptSection.of("Observation", observationText, 95, true),
                TokenPromptSection.of("Runtime Context",
                        StringUtils.hasText(runtimeContextBlock) ? runtimeContextBlock : "（无）", 30, false)
        ));
        String prompt = tokenBudget.getPrompt();
        try {
            String generated = chatGenerationService.generate(TokenGenerationRequest.builder()
                    .scene(TokenScene.AGENT_FINAL)
                    .traceId(traceId)
                    .prompt(prompt)
                    .budgetResult(tokenBudget)
                    .build()).getContent();
            return postProcessAnswer(StringUtils.hasText(generated) ? generated : fallbackAnswer(observations), question, observations);
        } catch (Exception ex) {
            return postProcessAnswer(fallbackAnswer(observations), question, observations);
        }
    }

    private String postProcessAnswer(String answer, String question, List<ToolQueryResultVO> observations) {
        return enforceQuestionFacts(enforceDeniedNotice(answer, observations), question);
    }

    private String enforceDeniedNotice(String answer, List<ToolQueryResultVO> observations) {
        List<String> deniedTools = observations.stream()
                .filter(one -> "denied".equalsIgnoreCase(one.getTarget())
                        || containsIgnoreCase(one.getSummary(), "不允许调用内部工具")
                        || containsIgnoreCase(one.getSummary(), "拒绝"))
                .map(ToolQueryResultVO::getTool)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (deniedTools.isEmpty() || containsIgnoreCase(answer, "拒绝")) {
            return answer;
        }
        return answer + "\n限制：已拒绝调用内部工具：" + String.join("、", deniedTools) + "；USER模式只能返回业务解释和公开知识库证据。";
    }

    private String enforceQuestionFacts(String answer, String question) {
        List<String> missing = extractQuestionFacts(question).stream()
                .filter(fact -> !containsIgnoreCase(answer, fact))
                .toList();
        if (missing.isEmpty()) {
            return answer;
        }
        return answer + "\n补充：用户当前问题已明确给出：" + String.join("、", missing) + "，回答应以这些当前商品事实为准。";
    }

    private List<String> extractQuestionFacts(String question) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        List<String> candidates = List.of(
                "RTX3060", "RTX 3060", "显卡", "跑分", "温度正常", "无发票",
                "全新未拆", "全新未拆封", "二手手机", "商品名称", "品类", "Apple"
        );
        List<String> facts = new ArrayList<>();
        for (String candidate : candidates) {
            if (containsIgnoreCase(question, candidate) && !facts.contains(candidate)) {
                facts.add(candidate);
            }
        }
        return facts;
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        return text != null && keyword != null
                && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String summarizeObservations(List<ToolQueryResultVO> observations) {
        List<String> lines = new ArrayList<>();
        for (ToolQueryResultVO one : observations) {
            lines.add("tool=" + one.getTool() + ", total=" + one.getTotal() + ", summary=" + one.getSummary());
            int count = 0;
            for (Map<String, Object> row : one.getRows()) {
                if (count++ >= MAX_OBSERVATION_ROWS_PER_TOOL) {
                    break;
                }
                lines.add("row=" + compactRow(row));
            }
        }
        return String.join("\n", lines);
    }

    private Map<String, Object> compactRow(Map<String, Object> row) {
        Map<String, Object> compact = new LinkedHashMap<>();
        copyIfPresent(row, compact, "docId");
        copyIfPresent(row, compact, "chunkId");
        copyIfPresent(row, compact, "sourceType");
        copyIfPresent(row, compact, "docTitle");
        copyIfPresent(row, compact, "score");
        copyIfPresent(row, compact, "summary");
        copyIfPresent(row, compact, "promptBlock");
        copyIfPresent(row, compact, "rawPreferences");
        copyIfPresent(row, compact, "snippet");
        if (compact.isEmpty()) {
            int copied = 0;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (copied++ >= 5) {
                    break;
                }
                compact.put(entry.getKey(), compactValue(entry.getValue()));
            }
        }
        compact.replaceAll((k, v) -> compactValue(v));
        return compact;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private Object compactValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String text = String.valueOf(value).replaceAll("\\s+", " ").trim();
        if (text.length() <= MAX_OBSERVATION_VALUE_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_OBSERVATION_VALUE_LENGTH) + "...";
    }

    private String fallbackAnswer(List<ToolQueryResultVO> observations) {
        return "已完成工具调用：" + observations.stream()
                .map(one -> one.getTool() + "(" + one.getSummary() + ")")
                .toList();
    }

    private List<RagSourceVO> extractSources(List<ToolQueryResultVO> observations) {
        Map<String, RagSourceVO> sources = new LinkedHashMap<>();
        for (ToolQueryResultVO observation : observations) {
            for (Map<String, Object> row : observation.getRows()) {
                Object docId = row.get("docId");
                if (!(docId instanceof Number number)) {
                    continue;
                }
                RagSourceVO source = new RagSourceVO();
                source.setDocId(number.longValue());
                source.setChunkId(row.get("chunkId") instanceof Number n ? n.longValue() : null);
                source.setScore(row.get("score") instanceof Number n ? n.doubleValue() : null);
                source.setSourceType(String.valueOf(row.getOrDefault("sourceType", "")));
                source.setSourceTrustLevel(String.valueOf(row.getOrDefault("sourceTrustLevel", "")));
                source.setDocVersion(String.valueOf(row.getOrDefault("docVersion", "")));
                source.setDocStatus(String.valueOf(row.getOrDefault("docStatus", "")));
                source.setEffectiveFrom(String.valueOf(row.getOrDefault("effectiveFrom", "")));
                source.setEffectiveTo(String.valueOf(row.getOrDefault("effectiveTo", "")));
                source.setDocTitle(String.valueOf(row.getOrDefault("docTitle", "")));
                source.setSnippet(String.valueOf(row.getOrDefault("snippet", "")));
                sources.put(source.getDocId() + "#" + source.getChunkId(), source);
            }
        }
        return new ArrayList<>(sources.values());
    }

    private List<Long> extractDocIds(ToolQueryResultVO observation) {
        Set<Long> ids = new HashSet<>();
        for (Map<String, Object> row : observation.getRows()) {
            Object docId = row.get("docId");
            if (docId instanceof Number number) {
                ids.add(number.longValue());
            }
        }
        return new ArrayList<>(ids);
    }

    private ToolQueryResultVO deniedObservation(String tool, String summary) {
        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool(tool);
        vo.setTarget("denied");
        vo.setLimit(0);
        vo.setTotal(0L);
        vo.setTruncated(false);
        vo.setElapsedMs(0L);
        vo.setSummary(summary);
        return vo;
    }

    private void addTrace(ReactAgentResponseVO response, String phase, String tool, String status, String thought, String action, String observation, Long elapsedMs, List<Long> docIds, Map<String, Object> metadata) {
        AgentTraceStepVO step = new AgentTraceStepVO();
        step.setIndex(response.getAgentTrace().size() + 1);
        step.setPhase(phase);
        step.setTool(tool);
        step.setStatus(status);
        step.setThought(thought);
        step.setAction(action);
        step.setObservation(observation);
        step.setElapsedMs(elapsedMs);
        step.setSourceDocIds(docIds == null ? List.of() : docIds);
        step.setMetadata(metadata);
        response.getAgentTrace().add(step);
    }

    private AgentAction action(String tool, String thought, Map<String, Object> input) {
        return new AgentAction(tool, thought, input);
    }

    private List<AgentAction> toActions(List<AgentPlanStep> steps) {
        return (steps == null ? List.<AgentPlanStep>of() : steps).stream()
                .map(step -> action(step.getTool(), step.getThought(), step.getInput()))
                .toList();
    }

    private List<AgentAction> dedup(List<AgentAction> actions) {
        Set<String> seen = new HashSet<>();
        List<AgentAction> out = new ArrayList<>();
        for (AgentAction action : actions) {
            if (seen.add(action.getTool())) {
                out.add(action);
            }
        }
        return out;
    }

    private boolean containsAny(String q, String... terms) {
        for (String term : terms) {
            if (q.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String safeSession(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId.trim() : "anonymous";
    }

    private String normalizeMode(String mode) {
        return "INTERNAL".equalsIgnoreCase(mode) ? "INTERNAL" : "USER";
    }

    private void validate(ReactAgentRequestDTO request) {
        if (request == null || !StringUtils.hasText(request.getQuestion())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "问题不能为空");
        }
    }
}
