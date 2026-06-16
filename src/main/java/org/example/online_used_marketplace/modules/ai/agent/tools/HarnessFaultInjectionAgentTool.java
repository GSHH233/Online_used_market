package org.example.online_used_marketplace.modules.ai.agent.tools;

import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class HarnessFaultInjectionAgentTool implements AgentTool {

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "harness_fault";
    }

    @Override
    public String description() {
        return "Harness故障注入测试工具，用于模拟成功、异常、超时、重试成功和快照恢复。";
    }

    @Override
    public boolean internalOnly() {
        return true;
    }

    @Override
    public ToolQueryResultVO execute(Map<String, Object> input) {
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        String key = text(safeInput.getOrDefault("scenarioKey", "default"));
        String behavior = text(safeInput.getOrDefault("behavior", "SUCCESS")).toUpperCase();
        int attempt = counters.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        int failTimes = number(safeInput.get("failTimes"), 1);
        long sleepMs = number(safeInput.get("sleepMs"), 0);

        sleepIfNeeded(sleepMs);

        if ("FAIL".equals(behavior)) {
            throw new IllegalStateException("fault injection failure, key=" + key + ", attempt=" + attempt);
        }
        if ("TIMEOUT".equals(behavior)) {
            sleepIfNeeded(Math.max(1000L, sleepMs));
            throw new IllegalStateException("fault injection timeout fallback guard, key=" + key);
        }
        if ("FAIL_THEN_SUCCESS".equals(behavior) && attempt <= failTimes) {
            throw new IllegalStateException("fault injection retry failure, key=" + key + ", attempt=" + attempt);
        }
        if ("SUCCESS_ONCE_THEN_FAIL".equals(behavior) && attempt > 1) {
            throw new IllegalStateException("fault injection snapshot failure, key=" + key + ", attempt=" + attempt);
        }

        return successObservation(key, behavior, attempt, safeInput);
    }

    private ToolQueryResultVO successObservation(String key, String behavior, int attempt, Map<String, Object> input) {
        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool(name());
        vo.setTarget(key);
        vo.setLimit(1);
        vo.setTotal(1L);
        vo.setTruncated(false);
        vo.setElapsedMs(0L);
        vo.setSummary("harness_fault成功: behavior=" + behavior + ", key=" + key + ", attempt=" + attempt);
        vo.setColumns(java.util.List.of("scenarioKey", "behavior", "attempt", "marker"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenarioKey", key);
        row.put("behavior", behavior);
        row.put("attempt", attempt);
        row.put("marker", text(input.getOrDefault("marker", "")));
        vo.getRows().add(row);
        return vo;
    }

    private void sleepIfNeeded(long sleepMs) {
        if (sleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fault injection interrupted");
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int number(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
