package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.agent.AgentToolRegistry;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContext;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContextBuilder;
import org.example.online_used_marketplace.modules.ai.dto.GraphAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.agent.harness.ToolExecutionHarness;
import org.example.online_used_marketplace.modules.ai.agent.harness.ToolExecutionResult;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.service.GraphReactAgentService;
import org.example.online_used_marketplace.modules.ai.service.SessionSummaryService;
import org.example.online_used_marketplace.modules.ai.vo.AgentGraphNodeVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentTraceStepVO;
import org.example.online_used_marketplace.modules.ai.vo.GraphAgentResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphReactAgentServiceImpl implements GraphReactAgentService {

    private static final int DEFAULT_MAX_STEPS = 5;
    private static final int MAX_OBSERVATION_ROWS_PER_TOOL = 2;
    private static final int MAX_OBSERVATION_VALUE_LENGTH = 140;

    private final AgentToolRegistry toolRegistry;
    private final ToolExecutionHarness toolExecutionHarness;
    private final ChatGenerationService chatGenerationService;
    private final SessionSummaryService sessionSummaryService;
    private final AgentRuntimeContextBuilder runtimeContextBuilder;

    @Override
    public GraphAgentResponseVO run(GraphAgentRequestDTO request) {
        validate(request);
        long start = System.currentTimeMillis();
        String question = request.getQuestion().trim();
        String mode = normalizeMode(request.getMode());
        String traceId = StringUtils.hasText(request.getTraceId()) ? request.getTraceId().trim() : UUID.randomUUID().toString();
        int maxSteps = request.getMaxSteps() == null ? DEFAULT_MAX_STEPS : Math.min(Math.max(request.getMaxSteps(), 1), 8);

        GraphAgentResponseVO response = new GraphAgentResponseVO();
        response.setTraceId(traceId);
        response.setQuestion(question);
        response.setMode(mode);

        AgentRuntimeContext runtimeContext = runtimeContextBuilder.build("graph", request.getSessionId(), traceId, mode, question, maxSteps);
        List<AgentGraphNodeVO> graph = planGraph(question, runtimeContext, traceId, maxSteps);
        response.setGraph(graph);
        Map<String, Object> planMetadata = new LinkedHashMap<>();
        planMetadata.put("availableTools", toolRegistry.names());
        planMetadata.put("planner", "rule_dag");
        planMetadata.put("runtimeContext", runtimeContext.safeMetadata());
        addTrace(response, "graph_plan", null, "SUCCESS", "根据问题生成DAG任务图",
                "nodes=" + graph.stream().map(AgentGraphNodeVO::getNodeId).toList(),
                "已生成规则版DAG执行计划", 0L, List.of(),
                planMetadata);

        executeGraph(response, graph, mode, runtimeContext, question);
        response.setSources(extractSources(response.getObservations()));
        if (!StringUtils.hasText(response.getAnswer())) {
            response.setAnswer(fallbackAnswer(response.getObservations()));
        }
        response.setSuccess(response.getGraph().stream().noneMatch(one -> "FAILED".equals(one.getStatus())));
        response.setElapsedMs(System.currentTimeMillis() - start);
        response.setDebugInfo("planner=rule_dag,nodes=" + graph.size()
                + ",toolNodes=" + graph.stream().filter(one -> "TOOL".equals(one.getType())).count()
                + ",runtimeContext=enabled,contextChars=" + runtimeContext.getPromptChars()
                + ",l2=" + runtimeContext.getL2MemoryLines().size()
                + ",graphMemory=" + runtimeContext.getGraphMemoryLines().size()
                + ",elapsedMs=" + response.getElapsedMs());
        if (!shouldUseFastOperationalAnswer(question, response.getObservations())) {
            sessionSummaryService.refreshSummaryIfNeeded(request.getSessionId(), question, response.getAnswer());
        }
        return response;
    }

    private List<AgentGraphNodeVO> planGraph(String question, AgentRuntimeContext runtimeContext, String traceId, int maxSteps) {
        String q = question.toLowerCase(Locale.ROOT);
        List<AgentGraphNodeVO> toolNodes = new ArrayList<>();

        if (isPrometheusIntent(q)) {
            toolNodes.add(toolNode("prometheus_query", "查询 Prometheus 指标 Observation，用于 AIOps 延迟、错误率或资源状态诊断", buildPrometheusInput(question)));
        }

        if (containsAny(q, "profile", "偏好", "画像", "用户喜欢", "历史偏好")) {
            toolNodes.add(toolNode("profile", "读取用户 Profile，避免历史偏好覆盖当前事实",
                    Map.of("userId", runtimeContext.getUserId(), "sessionId", runtimeContext.getSessionId())));
        }
        if (containsAny(q, "日志", "报错", "error", "trace")) {
            toolNodes.add(toolNode("log_query", "查询日志 Observation，用于定位报错或 trace",
                    Map.of("traceId", traceId, "limit", 50, "windowMinutes", 60)));
        }
        if (containsAny(q, "数据库", "mysql", "表", "索引任务", "文档表", "chunk")) {
            toolNodes.add(toolNode("mysql_read", "查询数据库状态，只执行白名单内只读 SQL",
                    Map.of("sql", "select id, title, status, created_time from ai_knowledge_doc order by id desc", "limit", 20)));
        }
        if (containsAny(q, "向量", "召回", "命中", "检索", "milvus", "source")) {
            toolNodes.add(toolNode("vector_debug", "查看向量/检索命中，辅助判断召回问题",
                    Map.of("question", question, "topK", 5)));
        }
        if (isKnowledgeSearchIntent(q)
                || toolNodes.isEmpty()) {
            toolNodes.add(toolNode("knowledge_search", "检索知识库规则、价格或描述模板作为证据",
                    Map.of("query", buildKnowledgeQuery(question, runtimeContext), "topK", 5)));
        }

        List<AgentGraphNodeVO> deduped = dedupTools(toolNodes).stream().limit(maxSteps).toList();
        List<AgentGraphNodeVO> graph = new ArrayList<>(deduped);

        AgentGraphNodeVO merge = new AgentGraphNodeVO();
        merge.setNodeId("observation_merge");
        merge.setType("MERGE");
        merge.setThought("等待工具 Observation 完成后进行证据合并");
        merge.setDependsOn(deduped.stream().map(AgentGraphNodeVO::getNodeId).toList());
        graph.add(merge);

        AgentGraphNodeVO finalNode = new AgentGraphNodeVO();
        finalNode.setNodeId("final_answer");
        finalNode.setType("FINAL");
        finalNode.setThought("基于合并后的 Observation 生成最终回答");
        finalNode.setDependsOn(List.of("observation_merge"));
        graph.add(finalNode);
        return graph;
    }

    private void executeGraph(GraphAgentResponseVO response, List<AgentGraphNodeVO> graph, String mode, AgentRuntimeContext runtimeContext, String question) {
        Map<String, AgentGraphNodeVO> nodeMap = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (AgentGraphNodeVO node : graph) {
            nodeMap.put(node.getNodeId(), node);
            indegree.put(node.getNodeId(), node.getDependsOn() == null ? 0 : node.getDependsOn().size());
            for (String dep : node.getDependsOn() == null ? List.<String>of() : node.getDependsOn()) {
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.getNodeId());
            }
        }

        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((nodeId, count) -> {
            if (count == 0) {
                ready.add(nodeId);
            }
        });

        int processed = 0;
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(4, Math.max(1, graph.size())));
        try {
            while (!ready.isEmpty()) {
                List<AgentGraphNodeVO> batch = new ArrayList<>();
                while (!ready.isEmpty()) {
                    batch.add(nodeMap.get(ready.removeFirst()));
                }

                List<CompletableFuture<NodeResult>> futures = batch.stream()
                        .map(node -> CompletableFuture.supplyAsync(() -> executeNode(response, node, mode, runtimeContext, question), executor))
                        .toList();
                List<NodeResult> results = futures.stream().map(CompletableFuture::join).toList();
                for (NodeResult result : results) {
                    processed++;
                    if (result.observation() != null) {
                        response.getObservations().add(result.observation());
                    }
                    addTrace(response, result.phase(), result.tool(), result.status(), result.thought(), result.action(),
                            result.observationSummary(), result.elapsedMs(), result.docIds(), result.metadata());
                    for (String next : dependents.getOrDefault(result.nodeId(), List.of())) {
                        int nextDegree = indegree.computeIfPresent(next, (k, v) -> v - 1);
                        if (nextDegree == 0) {
                            ready.add(next);
                        }
                    }
                }
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }

        if (processed != graph.size()) {
            addTrace(response, "graph_schedule", null, "FAILED", "DAG存在未执行节点，可能有循环依赖或非法依赖",
                    "processed=" + processed + ",total=" + graph.size(), "调度未完整完成", 0L, List.of(), Map.of());
        }
    }

    private NodeResult executeNode(GraphAgentResponseVO response, AgentGraphNodeVO node, String mode, AgentRuntimeContext runtimeContext, String question) {
        long start = System.currentTimeMillis();
        node.setStatus("RUNNING");
        try {
            if ("TOOL".equals(node.getType())) {
                return executeToolNode(node, mode, start);
            }
            if ("MERGE".equals(node.getType())) {
                node.setStatus("SUCCESS");
                node.setSummary("已合并 " + response.getObservations().size() + " 个工具 Observation");
                return new NodeResult(node.getNodeId(), "merge", null, "SUCCESS", node.getThought(), node.getNodeId(),
                        node.getSummary(), System.currentTimeMillis() - start, List.of(), Map.of("observationCount", response.getObservations().size()), null);
            }
            if ("FINAL".equals(node.getType())) {
                String answer = buildFinalAnswer(question, runtimeContext, response.getObservations());
                response.setAnswer(answer);
                node.setStatus("SUCCESS");
                node.setSummary("最终回答已生成");
                return new NodeResult(node.getNodeId(), "final", null, "SUCCESS", node.getThought(), node.getNodeId(),
                        node.getSummary(), System.currentTimeMillis() - start, extractSources(response.getObservations()).stream()
                        .map(RagSourceVO::getDocId).filter(java.util.Objects::nonNull).toList(), Map.of(), null);
            }
            node.setStatus("SKIPPED");
            node.setSummary("未知节点类型：" + node.getType());
            return new NodeResult(node.getNodeId(), "graph_node", null, "SKIPPED", node.getThought(), node.getNodeId(),
                    node.getSummary(), System.currentTimeMillis() - start, List.of(), Map.of(), null);
        } catch (Exception ex) {
            log.warn("graph agent node failed, node={}, message={}", node.getNodeId(), ex.getMessage());
            node.setStatus("FAILED");
            node.setSummary("节点执行失败：" + ex.getMessage());
            return new NodeResult(node.getNodeId(), "graph_node", node.getTool(), "FAILED", node.getThought(), node.getNodeId(),
                    node.getSummary(), System.currentTimeMillis() - start, List.of(), Map.of(), null);
        } finally {
            node.setElapsedMs(System.currentTimeMillis() - start);
        }
    }

    private NodeResult executeToolNode(AgentGraphNodeVO node, String mode, long start) {
        AgentTool tool = toolRegistry.find(node.getTool())
                .orElseThrow(() -> new BusinessException(ResultCode.BAD_REQUEST, "未知工具：" + node.getTool()));
        if (tool.internalOnly() && !"INTERNAL".equals(mode)) {
            ToolQueryResultVO denied = deniedObservation(tool.name(), "拒绝：USER模式不允许调用内部工具");
            node.setStatus("DENIED");
            node.setSummary(denied.getSummary());
            return new NodeResult(node.getNodeId(), "tool_call", tool.name(), "DENIED", node.getThought(),
                    String.valueOf(node.getInput()), denied.getSummary(), System.currentTimeMillis() - start,
                    List.of(), Map.of("mode", mode), denied);
        }
        ToolExecutionResult execution = toolExecutionHarness.execute(tool, node.getInput() == null ? Collections.emptyMap() : node.getInput(), mode);
        ToolQueryResultVO observation = execution.getObservation();
        node.setStatus(traceStatus(execution));
        node.setSummary(observation.getSummary());
        Map<String, Object> metadata = new LinkedHashMap<>(execution.metadata());
        metadata.put("total", observation.getTotal());
        return new NodeResult(node.getNodeId(), "tool_call", tool.name(), traceStatus(execution), node.getThought(),
                String.valueOf(node.getInput()), observation.getSummary(), System.currentTimeMillis() - start,
                extractDocIds(observation), metadata, observation);
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

    private String buildFinalAnswer(String question, AgentRuntimeContext runtimeContext, List<ToolQueryResultVO> observations) {
        if (shouldUseFastOperationalAnswer(question, observations)) {
            return postProcessAnswer(buildFastOperationalAnswer(observations), question, observations);
        }
        String observationText = summarizeObservations(observations);
        String runtimeContextBlock = runtimeContext == null ? "" : runtimeContext.getPromptBlock();
        String prompt = """
                你是 Graph ReAct 工具 Agent。请基于 DAG 节点 Observation 回答用户问题。
                规则：
                1. 必须说明用了哪些工具和关键观察结果。
                2. 不要编造 Observation 之外的金额、规则、日志或数据库结果。
                3. 如果工具被拒绝或失败，明确说明限制，并给出下一步建议。
                4. 回答保持简洁，优先给结论，尽量控制在 180 字以内。
                5. 用户问题里明确给出的当前事实必须保留，不要被历史偏好覆盖。
                6. Runtime Context 只能用于多轮延续、默认参数和表达偏好，不得覆盖本轮用户输入和 Observation。
                Runtime Context:
                %s
                用户问题:
                %s
                Observation:
                %s
                """.formatted(StringUtils.hasText(runtimeContextBlock) ? runtimeContextBlock : "（无）", question, observationText);
        try {
            String generated = chatGenerationService.generate(prompt);
            return postProcessAnswer(StringUtils.hasText(generated) ? generated : fallbackAnswer(observations), question, observations);
        } catch (Exception ex) {
            return postProcessAnswer(fallbackAnswer(observations), question, observations);
        }
    }

    private boolean shouldUseFastOperationalAnswer(String question, List<ToolQueryResultVO> observations) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (containsAny(q, "aiops", "prometheus", "promql", "monitor", "alert", "p95", "p99", "latency", "trace",
                "mysql", "rag", "source", "指标", "监控", "告警", "延迟", "耗时", "错误率", "连接池", "日志", "召回")) {
            return true;
        }
        return observations.stream()
                .map(ToolQueryResultVO::getTool)
                .filter(StringUtils::hasText)
                .anyMatch(tool -> containsAny(tool.toLowerCase(Locale.ROOT),
                        "prometheus_query", "log_query", "mysql_read", "vector_debug"));
    }

    private String buildFastOperationalAnswer(List<ToolQueryResultVO> observations) {
        List<String> tools = observations.stream()
                .map(ToolQueryResultVO::getTool)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        List<String> summaries = observations.stream()
                .filter(one -> StringUtils.hasText(one.getSummary()))
                .limit(5)
                .map(one -> one.getTool() + ": " + compactText(one.getSummary(), 80))
                .toList();

        List<String> conclusions = new ArrayList<>();
        if (hasTool(observations, "prometheus_query")) {
            conclusions.add("Prometheus 指标已返回，可作为当前监控证据");
        }
        if (observations.stream().anyMatch(one -> "log_query".equals(one.getTool())
                && StringUtils.hasText(one.getSummary())
                && containsAny(one.getSummary(), "不存在", "失败", "无"))) {
            conclusions.add("日志证据不足，trace 根因仍需补查真实日志");
        }
        if (hasTool(observations, "vector_debug")) {
            conclusions.add("已检查向量/RAG source 命中情况");
        }
        if (hasTool(observations, "knowledge_search")) {
            conclusions.add("已检索知识库规则作为业务证据");
        }
        if (hasTool(observations, "mysql_read")) {
            conclusions.add("已执行 MySQL 只读查询检查数据状态");
        }
        if (conclusions.isEmpty()) {
            conclusions.add("工具链路已完成，建议结合 Observation 继续定位");
        }

        return "快速诊断：已使用 " + String.join("、", tools) + "。"
                + "\n关键观察：" + String.join("；", summaries) + "。"
                + "\n结论：" + String.join("；", conclusions) + "。";
    }

    private boolean hasTool(List<ToolQueryResultVO> observations, String tool) {
        return observations.stream().anyMatch(one -> tool.equals(one.getTool()));
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
        return expansions.isEmpty() ? query : query + "\n检索补充词：" + String.join(" ", expansions);
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
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return compactText(String.valueOf(value), MAX_OBSERVATION_VALUE_LENGTH);
    }

    private String compactText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    private String postProcessAnswer(String answer, String question, List<ToolQueryResultVO> observations) {
        return enforceQuestionFacts(enforceDeniedNotice(answer, observations), question);
    }

    private String enforceDeniedNotice(String answer, List<ToolQueryResultVO> observations) {
        List<String> deniedTools = observations.stream()
                .filter(one -> "denied".equalsIgnoreCase(one.getTarget()) || containsIgnoreCase(one.getSummary(), "拒绝"))
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
        List<String> candidates = List.of("RTX3060", "RTX 3060", "显卡", "跑分", "温度正常", "无发票",
                "全新未拆", "全新未拆封", "二手手机", "相机", "快门", "无人机", "电池", "配件");
        List<String> facts = new ArrayList<>();
        for (String candidate : candidates) {
            if (containsIgnoreCase(question, candidate) && !facts.contains(candidate)) {
                facts.add(candidate);
            }
        }
        return facts;
    }

    private String fallbackAnswer(List<ToolQueryResultVO> observations) {
        return "已完成DAG工具调用：" + observations.stream()
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

    private void addTrace(GraphAgentResponseVO response, String phase, String tool, String status, String thought, String action,
                          String observation, Long elapsedMs, List<Long> docIds, Map<String, Object> metadata) {
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

    private AgentGraphNodeVO toolNode(String tool, String thought, Map<String, Object> input) {
        AgentGraphNodeVO node = new AgentGraphNodeVO();
        node.setNodeId(tool);
        node.setType("TOOL");
        node.setTool(tool);
        node.setThought(thought);
        node.setInput(input);
        return node;
    }

    private List<AgentGraphNodeVO> dedupTools(List<AgentGraphNodeVO> nodes) {
        Set<String> seen = new HashSet<>();
        List<AgentGraphNodeVO> out = new ArrayList<>();
        for (AgentGraphNodeVO node : nodes) {
            if (seen.add(node.getTool())) {
                out.add(node);
            }
        }
        return out;
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

    private boolean containsAny(String q, String... terms) {
        if (q == null) {
            return false;
        }
        for (String term : terms) {
            if (q.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        return text != null && keyword != null
                && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String safeSession(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId.trim() : "anonymous";
    }

    private String normalizeMode(String mode) {
        return "INTERNAL".equalsIgnoreCase(mode) ? "INTERNAL" : "USER";
    }

    private void validate(GraphAgentRequestDTO request) {
        if (request == null || !StringUtils.hasText(request.getQuestion())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "问题不能为空");
        }
    }

    private record NodeResult(
            String nodeId,
            String phase,
            String tool,
            String status,
            String thought,
            String action,
            String observationSummary,
            Long elapsedMs,
            List<Long> docIds,
            Map<String, Object> metadata,
            ToolQueryResultVO observation
    ) {
    }
}
