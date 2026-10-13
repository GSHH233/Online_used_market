package org.example.online_used_marketplace.modules.ai.agent.planner;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.agent.AgentToolRegistry;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AgentPlanValidator {

    private static final String SAFE_KNOWLEDGE_SQL =
            "select id, title, status, created_time from ai_knowledge_doc order by id desc";

    private final AgentToolRegistry toolRegistry;

    public ValidationResult validate(List<AgentPlanStep> proposed,
                                     String question,
                                     AgentRuntimeContext runtimeContext,
                                     String traceId,
                                     String mode,
                                     int maxSteps,
                                     String idPrefix) {
        List<AgentPlanStep> accepted = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> tools = new HashSet<>();
        Set<String> ids = new HashSet<>();

        for (AgentPlanStep candidate : proposed == null ? List.<AgentPlanStep>of() : proposed) {
            if (accepted.size() >= Math.max(0, maxSteps)) {
                warnings.add("plan truncated by maxSteps=" + maxSteps);
                break;
            }
            if (candidate == null || !StringUtils.hasText(candidate.getTool())) {
                warnings.add("ignored step without tool");
                continue;
            }
            String toolName = candidate.getTool().trim();
            AgentTool tool = toolRegistry.find(toolName).orElse(null);
            if (tool == null) {
                warnings.add("ignored unknown tool=" + toolName);
                continue;
            }
            if ("harness_fault".equals(toolName)) {
                warnings.add("ignored non-production tool=harness_fault");
                continue;
            }
            if ("profile".equals(toolName)
                    && (runtimeContext == null || !StringUtils.hasText(runtimeContext.getUserId()))) {
                warnings.add("ignored profile tool without authenticated owner");
                continue;
            }
            if (tool.internalOnly() && !"INTERNAL".equalsIgnoreCase(mode)) {
                // Keep the step so the executor emits an explicit DENIED observation and audit trace.
                warnings.add("internal tool will be denied by executor in USER mode=" + toolName);
            }
            if (!tools.add(toolName)) {
                warnings.add("ignored duplicate tool=" + toolName);
                continue;
            }

            String id = safeId(candidate.getId(), toolName, idPrefix, ids);
            List<String> dependencies = new ArrayList<>();
            for (String dependency : candidate.getDependsOn() == null
                    ? List.<String>of() : candidate.getDependsOn()) {
                String normalized = normalizeDependency(dependency, idPrefix);
                if (ids.contains(normalized) && !normalized.equals(id)) {
                    dependencies.add(normalized);
                } else {
                    warnings.add("ignored forward or unknown dependency=" + dependency);
                }
            }

            AgentPlanStep safe = new AgentPlanStep();
            safe.setId(id);
            safe.setTool(toolName);
            safe.setThought(safeThought(candidate.getThought(), tool.description()));
            safe.setDependsOn(dependencies);
            safe.setInput(safeInput(toolName, candidate.getInput(), question, runtimeContext, traceId));
            accepted.add(safe);
            ids.add(id);
        }
        return new ValidationResult(accepted, warnings);
    }

    private Map<String, Object> safeInput(String tool,
                                          Map<String, Object> proposed,
                                          String question,
                                          AgentRuntimeContext runtimeContext,
                                          String traceId) {
        Map<String, Object> input = proposed == null ? Map.of() : proposed;
        return switch (tool) {
            case "knowledge_search" -> Map.of(
                    "query", boundedText(input.get("query"), question, 600),
                    "topK", boundedInt(input.get("topK"), 5, 1, 10)
            );
            case "profile" -> Map.of(
                    "userId", runtimeContext == null ? "anonymous" : runtimeContext.getUserId(),
                    "sessionId", runtimeContext == null ? "" : runtimeContext.getSessionId()
            );
            case "log_query" -> {
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("traceId", traceId);
                safe.put("limit", boundedInt(input.get("limit"), 50, 1, 100));
                safe.put("windowMinutes", boundedInt(input.get("windowMinutes"), 60, 1, 1440));
                yield safe;
            }
            case "mysql_read" -> Map.of("sql", SAFE_KNOWLEDGE_SQL, "limit", 20);
            case "vector_debug" -> Map.of(
                    "question", question,
                    "topK", boundedInt(input.get("topK"), 5, 1, 10)
            );
            case "prometheus_query" -> Map.of(
                    "query", selectPrometheusQuery(question),
                    "queryType", "instant",
                    "limit", 20
            );
            default -> Map.of();
        };
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

    private String safeId(String proposed, String tool, String prefix, Set<String> ids) {
        String base = StringUtils.hasText(proposed)
                ? proposed.trim().replaceAll("[^a-zA-Z0-9_-]", "_")
                : tool;
        if (!StringUtils.hasText(base)) {
            base = tool;
        }
        String normalizedPrefix = StringUtils.hasText(prefix) ? prefix.trim() : "";
        String id = normalizedPrefix + base;
        int suffix = 2;
        while (ids.contains(id)) {
            id = normalizedPrefix + base + "_" + suffix++;
        }
        return id;
    }

    private String normalizeDependency(String dependency, String prefix) {
        if (!StringUtils.hasText(dependency)) {
            return "";
        }
        String safe = dependency.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return (StringUtils.hasText(prefix) ? prefix.trim() : "") + safe;
    }

    private String safeThought(String thought, String fallback) {
        String value = StringUtils.hasText(thought) ? thought.trim() : fallback;
        return value.length() <= 240 ? value : value.substring(0, 240);
    }

    private String boundedText(Object value, String fallback, int maxLength) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) {
            text = fallback == null ? "" : fallback.trim();
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private int boundedInt(Object value, int fallback, int min, int max) {
        int parsed = value instanceof Number number ? number.intValue() : fallback;
        return Math.min(max, Math.max(min, parsed));
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public record ValidationResult(List<AgentPlanStep> steps, List<String> warnings) {
    }
}
