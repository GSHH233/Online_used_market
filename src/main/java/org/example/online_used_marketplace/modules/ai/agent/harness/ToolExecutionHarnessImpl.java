package org.example.online_used_marketplace.modules.ai.agent.harness;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionHarnessImpl implements ToolExecutionHarness {

    private final ToolExecutionHarnessProperties properties;
    private final ToolExecutionSnapshotStore snapshotStore;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final ExecutorService executor = Executors.newCachedThreadPool(new HarnessThreadFactory());

    @Override
    public ToolExecutionResult execute(AgentTool tool, Map<String, Object> input, String mode) {
        Objects.requireNonNull(tool, "tool must not be null");
        Map<String, Object> safeInput = input == null ? Collections.emptyMap() : input;
        ToolExecutionPolicy policy = properties.policyFor(tool.name());
        if (!properties.isEnabled() || !policy.isEnabled()) {
            long start = System.currentTimeMillis();
            ToolQueryResultVO observation = tool.execute(safeInput);
            return recordMetrics(ToolExecutionResult.builder()
                    .tool(tool.name())
                    .status("SUCCESS")
                    .observation(observation)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .attempts(1)
                    .retryCount(0)
                    .timeout(false)
                    .fallbackUsed(false)
                    .snapshotRestored(false)
                    .build());
        }

        long start = System.currentTimeMillis();
        String snapshotKey = snapshotKey(tool.name(), mode, safeInput);
        int maxAttempts = Math.max(1, policy.getMaxRetries() + 1);
        Throwable lastError = null;
        boolean timeout = false;
        int attempts = 0;
        for (int i = 1; i <= maxAttempts; i++) {
            attempts = i;
            try {
                ToolQueryResultVO observation = callWithTimeout(tool, safeInput, policy.getTimeoutMs());
                if (policy.isSnapshotEnabled()) {
                    snapshotStore.save(snapshotKey, copyObservation(observation), Duration.ofSeconds(Math.max(1L, policy.getSnapshotTtlSeconds())));
                }
                return recordMetrics(ToolExecutionResult.builder()
                        .tool(tool.name())
                        .status("SUCCESS")
                        .observation(observation)
                        .elapsedMs(System.currentTimeMillis() - start)
                        .attempts(attempts)
                        .retryCount(attempts - 1)
                        .timeout(false)
                        .fallbackUsed(false)
                        .snapshotRestored(false)
                        .build());
            } catch (Throwable ex) {
                lastError = unwrap(ex);
                timeout = timeout || lastError instanceof TimeoutException;
                log.warn("tool execution failed, tool={}, attempt={}/{}, timeout={}, message={}",
                        tool.name(), attempts, maxAttempts, lastError instanceof TimeoutException, lastError.getMessage());
                if (i < maxAttempts) {
                    sleepBackoff(policy.getRetryBackoffMs(), i);
                }
            }
        }

        Optional<ToolQueryResultVO> snapshot = policy.isSnapshotEnabled() ? restoreSnapshot(snapshotKey) : Optional.empty();
        if (snapshot.isPresent()) {
            ToolQueryResultVO observation = snapshot.get();
            observation.setSummary(prefixSummary("已从工具快照恢复：", observation.getSummary()));
            return recordMetrics(ToolExecutionResult.builder()
                    .tool(tool.name())
                    .status("SNAPSHOT_RESTORED")
                    .observation(observation)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .attempts(attempts)
                    .retryCount(Math.max(0, attempts - 1))
                    .timeout(timeout)
                    .fallbackUsed(false)
                    .snapshotRestored(true)
                    .errorMessage(errorMessage(lastError))
                    .build());
        }

        ToolQueryResultVO fallback = fallbackObservation(tool.name(), policy, safeInput, lastError, timeout, System.currentTimeMillis() - start);
        return recordMetrics(ToolExecutionResult.builder()
                .tool(tool.name())
                .status(timeout ? "TIMEOUT_FALLBACK" : "FALLBACK")
                .observation(fallback)
                .elapsedMs(System.currentTimeMillis() - start)
                .attempts(attempts)
                .retryCount(Math.max(0, attempts - 1))
                .timeout(timeout)
                .fallbackUsed(true)
                .snapshotRestored(false)
                .errorMessage(errorMessage(lastError))
                .build());
    }

    private ToolExecutionResult recordMetrics(ToolExecutionResult result) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null || result == null) {
            return result;
        }
        String tool = tagValue(result.getTool());
        String status = tagValue(result.getStatus());
        Timer.builder("ai.agent.tool.execution")
                .description("Agent tool execution latency guarded by Harness")
                .tag("tool", tool)
                .tag("status", status)
                .register(registry)
                .record(Duration.ofMillis(Math.max(0L, result.getElapsedMs())));
        registry.counter("ai.agent.tool.execution.total", "tool", tool, "status", status).increment();
        if (result.getRetryCount() > 0) {
            registry.counter("ai.agent.tool.retry.total", "tool", tool, "status", status).increment(result.getRetryCount());
        }
        if (result.isTimeout()) {
            registry.counter("ai.agent.tool.timeout.total", "tool", tool, "status", status).increment();
        }
        if (result.isFallbackUsed()) {
            registry.counter("ai.agent.tool.fallback.total", "tool", tool, "status", status).increment();
        }
        if (result.isSnapshotRestored()) {
            registry.counter("ai.agent.tool.snapshot.restore.total", "tool", tool, "status", status).increment();
        }
        return result;
    }

    private String tagValue(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private ToolQueryResultVO callWithTimeout(AgentTool tool, Map<String, Object> input, long timeoutMs) throws Exception {
        CompletableFuture<ToolQueryResultVO> future = CompletableFuture.supplyAsync(() -> tool.execute(input), executor);
        try {
            return future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        }
    }

    private void sleepBackoff(long retryBackoffMs, int attempt) {
        long delay = Math.max(0L, retryBackoffMs) * Math.max(1, attempt);
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private Optional<ToolQueryResultVO> restoreSnapshot(String snapshotKey) {
        return snapshotStore.find(snapshotKey).map(this::copyObservation);
    }

    private ToolQueryResultVO fallbackObservation(String tool, ToolExecutionPolicy policy, Map<String, Object> input,
                                                 Throwable lastError, boolean timeout, long elapsedMs) {
        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool(tool);
        vo.setTarget("harness_fallback");
        vo.setLimit(0);
        vo.setTotal(0L);
        vo.setTruncated(false);
        vo.setElapsedMs(elapsedMs);
        String reason = timeout ? "工具调用超时" : "工具调用失败";
        vo.setSummary(policy.getFallbackSummary() + "；原因=" + reason + formatError(lastError));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fallback", true);
        row.put("timeout", timeout);
        row.put("tool", tool);
        row.put("inputHash", shortHash(String.valueOf(input)));
        if (lastError != null) {
            row.put("error", errorMessage(lastError));
        }
        vo.getRows().add(row);
        return vo;
    }

    private String prefixSummary(String prefix, String summary) {
        if (summary == null || summary.isBlank()) {
            return prefix.trim();
        }
        return prefix + summary;
    }

    private String formatError(Throwable ex) {
        String message = errorMessage(ex);
        return message.isBlank() ? "" : "；错误=" + message;
    }

    private Throwable unwrap(Throwable ex) {
        if (ex instanceof CompletionException && ex.getCause() != null) {
            return unwrap(ex.getCause());
        }
        if (ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null) {
            return unwrap(ex.getCause());
        }
        return ex;
    }

    private String errorMessage(Throwable ex) {
        if (ex == null) {
            return "";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private String snapshotKey(String tool, String mode, Map<String, Object> input) {
        return tool + ":" + shortHash((mode == null ? "" : mode) + ":" + normalize(input));
    }

    private String shortHash(String text) {
        return sha256(text).substring(0, 16);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private String normalize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> entry : entries) {
                parts.add(String.valueOf(entry.getKey()) + "=" + normalize(entry.getValue()));
            }
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> parts = new ArrayList<>();
            for (Object one : iterable) {
                parts.add(normalize(one));
            }
            return "[" + String.join(",", parts) + "]";
        }
        return String.valueOf(value);
    }

    private ToolQueryResultVO copyObservation(ToolQueryResultVO src) {
        ToolQueryResultVO out = new ToolQueryResultVO();
        out.setTool(src.getTool());
        out.setTarget(src.getTarget());
        out.setLimit(src.getLimit());
        out.setTotal(src.getTotal());
        out.setTruncated(src.getTruncated());
        out.setElapsedMs(src.getElapsedMs());
        out.setColumns(src.getColumns() == null ? new ArrayList<>() : new ArrayList<>(src.getColumns()));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (src.getRows() != null) {
            for (Map<String, Object> row : src.getRows()) {
                rows.add(row == null ? new LinkedHashMap<>() : new LinkedHashMap<>(row));
            }
        }
        out.setRows(rows);
        out.setSummary(src.getSummary());
        return out;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static class HarnessThreadFactory implements ThreadFactory {
        private int count = 0;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ai-tool-harness-" + (++count));
            thread.setDaemon(true);
            return thread;
        }
    }
}
