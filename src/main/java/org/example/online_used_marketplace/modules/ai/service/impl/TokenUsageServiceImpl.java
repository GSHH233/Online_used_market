package org.example.online_used_marketplace.modules.ai.service.impl;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.TokenGovernanceProperties;
import org.example.online_used_marketplace.modules.ai.service.TokenUsageService;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.example.online_used_marketplace.modules.ai.token.TokenUsageRecord;
import org.example.online_used_marketplace.modules.ai.vo.TokenUsageMetricsVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Service
public class TokenUsageServiceImpl implements TokenUsageService {

    private static final String SELECT_COLUMNS = """
            SELECT id, trace_id, scene, model, prompt_tokens, completion_tokens, total_tokens,
                   input_budget, output_budget, original_prompt_tokens, trimmed_tokens,
                   usage_estimated, attempts, elapsed_ms, status, error_message, created_time
            FROM ai_token_usage
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TokenGovernanceProperties properties;
    private final MeterRegistry meterRegistry;
    private final ConcurrentLinkedDeque<TokenUsageRecord> recent = new ConcurrentLinkedDeque<>();

    public TokenUsageServiceImpl(@Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate,
                                 TokenGovernanceProperties properties,
                                 MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void record(TokenUsageRecord record) {
        if (record == null) {
            return;
        }
        if (record.getCreatedTime() == null) {
            record.setCreatedTime(LocalDateTime.now());
        }
        recent.addFirst(record);
        while (recent.size() > Math.max(20, properties.getRecentMemoryLimit())) {
            recent.pollLast();
        }
        recordMetrics(record);
        if (!properties.isPersistEnabled()) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO ai_token_usage(
                        trace_id, scene, model, prompt_tokens, completion_tokens, total_tokens,
                        input_budget, output_budget, original_prompt_tokens, trimmed_tokens,
                        usage_estimated, attempts, elapsed_ms, status, error_message, created_time
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    safe(record.getTraceId()),
                    record.getScene() == null ? TokenScene.GENERAL.name() : record.getScene().name(),
                    safe(record.getModel()),
                    record.getPromptTokens(),
                    record.getCompletionTokens(),
                    record.getTotalTokens(),
                    record.getInputBudget(),
                    record.getOutputBudget(),
                    record.getOriginalPromptTokens(),
                    record.getTrimmedTokens(),
                    record.isUsageEstimated(),
                    record.getAttempts(),
                    record.getElapsedMs(),
                    safe(record.getStatus()),
                    safe(record.getErrorMessage()),
                    Timestamp.valueOf(record.getCreatedTime()));
        } catch (Exception ex) {
            log.warn("[TokenGovernance] usage persistence skipped: {}", ex.getMessage());
        }
    }

    @Override
    public List<TokenUsageRecord> recent(String traceId, Integer limit) {
        int normalizedLimit = Math.min(Math.max(limit == null ? 50 : limit, 1), 500);
        if (properties.isPersistEnabled()) {
            try {
                if (StringUtils.hasText(traceId)) {
                    return jdbcTemplate.query(SELECT_COLUMNS
                                    + " WHERE trace_id = ? ORDER BY created_time DESC LIMIT ?",
                            this::mapRow, traceId.trim(), normalizedLimit);
                }
                return jdbcTemplate.query(SELECT_COLUMNS + " ORDER BY created_time DESC LIMIT ?",
                        this::mapRow, normalizedLimit);
            } catch (Exception ex) {
                log.debug("[TokenGovernance] usage query fallback to memory: {}", ex.getMessage());
            }
        }
        return recent.stream()
                .filter(record -> !StringUtils.hasText(traceId) || traceId.trim().equals(record.getTraceId()))
                .limit(normalizedLimit)
                .toList();
    }

    @Override
    public TokenUsageMetricsVO metrics(Integer recentLimit) {
        List<TokenUsageRecord> records = recent(null, recentLimit == null ? 100 : recentLimit);
        TokenUsageMetricsVO metrics = new TokenUsageMetricsVO();
        metrics.setRequestCount(records.size());
        metrics.setSuccessCount((int) records.stream().filter(this::isSuccess).count());
        metrics.setActualUsageCount((int) records.stream().filter(record -> !record.isUsageEstimated()).count());
        metrics.setEstimatedUsageCount((int) records.stream().filter(TokenUsageRecord::isUsageEstimated).count());
        metrics.setPromptTokens(records.stream().mapToLong(TokenUsageRecord::getPromptTokens).sum());
        metrics.setCompletionTokens(records.stream().mapToLong(TokenUsageRecord::getCompletionTokens).sum());
        metrics.setTotalTokens(records.stream().mapToLong(TokenUsageRecord::getTotalTokens).sum());
        metrics.setTrimmedTokens(records.stream().mapToLong(TokenUsageRecord::getTrimmedTokens).sum());
        metrics.setAverageTokens(round(records.stream().mapToInt(TokenUsageRecord::getTotalTokens).average().orElse(0D)));
        metrics.setP95Tokens(round(percentile(records, 0.95D)));
        long compliant = records.stream()
                .filter(record -> (record.getInputBudget() <= 0
                        || record.getOriginalPromptTokens() - record.getTrimmedTokens() <= record.getInputBudget())
                        && (record.getOutputBudget() <= 0
                        || record.getCompletionTokens() <= record.getOutputBudget()))
                .count();
        metrics.setBudgetCompliantRate(records.isEmpty() ? 1D : round((double) compliant / records.size()));
        records.forEach(record -> metrics.getSceneTokens().merge(
                record.getScene() == null ? TokenScene.GENERAL.name() : record.getScene().name(),
                (long) record.getTotalTokens(),
                Long::sum));
        return metrics;
    }

    private void recordMetrics(TokenUsageRecord record) {
        String scene = record.getScene() == null ? TokenScene.GENERAL.name() : record.getScene().name();
        String status = StringUtils.hasText(record.getStatus()) ? record.getStatus() : "UNKNOWN";
        meterRegistry.counter("ai.token.requests", "scene", scene, "status", status).increment();
        meterRegistry.counter("ai.token.prompt", "scene", scene).increment(Math.max(0, record.getPromptTokens()));
        meterRegistry.counter("ai.token.completion", "scene", scene).increment(Math.max(0, record.getCompletionTokens()));
        meterRegistry.counter("ai.token.trimmed", "scene", scene).increment(Math.max(0, record.getTrimmedTokens()));
        meterRegistry.summary("ai.token.total", "scene", scene).record(Math.max(0, record.getTotalTokens()));
    }

    private double percentile(List<TokenUsageRecord> records, double percentile) {
        if (records.isEmpty()) {
            return 0D;
        }
        List<Integer> values = new ArrayList<>(records.stream()
                .map(TokenUsageRecord::getTotalTokens)
                .sorted(Comparator.naturalOrder())
                .toList());
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private TokenUsageRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_time");
        return TokenUsageRecord.builder()
                .id(rs.getLong("id"))
                .traceId(rs.getString("trace_id"))
                .scene(TokenScene.from(rs.getString("scene")))
                .model(rs.getString("model"))
                .promptTokens(rs.getInt("prompt_tokens"))
                .completionTokens(rs.getInt("completion_tokens"))
                .totalTokens(rs.getInt("total_tokens"))
                .inputBudget(rs.getInt("input_budget"))
                .outputBudget(rs.getInt("output_budget"))
                .originalPromptTokens(rs.getInt("original_prompt_tokens"))
                .trimmedTokens(rs.getInt("trimmed_tokens"))
                .usageEstimated(rs.getBoolean("usage_estimated"))
                .attempts(rs.getInt("attempts"))
                .elapsedMs(rs.getLong("elapsed_ms"))
                .status(rs.getString("status"))
                .errorMessage(rs.getString("error_message"))
                .createdTime(created == null ? null : created.toLocalDateTime())
                .build();
    }

    private boolean isSuccess(TokenUsageRecord record) {
        return "SUCCESS".equalsIgnoreCase(record.getStatus());
    }

    private double round(double value) {
        return Math.round(value * 10_000D) / 10_000D;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
