package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.service.AgentTracePersistenceService;
import org.example.online_used_marketplace.modules.ai.service.EvalDashboardService;
import org.example.online_used_marketplace.modules.ai.vo.EvalDashboardVO;
import org.example.online_used_marketplace.modules.ai.vo.EvalReportSummaryVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class EvalDashboardServiceImpl implements EvalDashboardService {

    private static final Pattern REPORT_PATTERN = Pattern.compile("(.+?)_eval_report_\\d{8}-\\d{6}\\.md");
    private static final Pattern METRIC_PATTERN = Pattern.compile("^-\\s*([^:：]+)[:：]\\s*(.+)$");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AgentTracePersistenceService tracePersistenceService;

    public EvalDashboardServiceImpl(
            @Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AgentTracePersistenceService tracePersistenceService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.tracePersistenceService = tracePersistenceService;
    }

    @Override
    public EvalDashboardVO dashboard(Integer recentLimit) {
        syncMarkdownReportsToPg();
        int limit = Math.max(1, Math.min(recentLimit == null ? 20 : recentLimit, 100));
        List<EvalReportSummaryVO> recent = queryReports(limit);
        Map<String, EvalReportSummaryVO> latestByType = new LinkedHashMap<>();
        for (EvalReportSummaryVO report : queryReports(500)) {
            latestByType.putIfAbsent(report.getEvalType(), report);
        }

        EvalDashboardVO vo = new EvalDashboardVO();
        vo.setTotalReports(countReports());
        vo.setEvalTypes(latestByType.keySet().stream().toList());
        vo.setLatestByType(latestByType.values().stream().toList());
        vo.setRecentReports(recent);
        vo.setTraceStats(tracePersistenceService.stats());
        return vo;
    }

    private void syncMarkdownReportsToPg() {
        for (Path path : loadReportPaths()) {
            try {
                upsertReport(path);
            } catch (Exception ex) {
                log.warn("[EvalDashboard] sync report skipped, file={}, reason={}", path.getFileName(), ex.getMessage());
            }
        }
    }

    private List<Path> loadReportPaths() {
        Path root = Path.of(System.getProperty("user.dir"));
        try (var stream = Files.list(root)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && REPORT_PATTERN.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void upsertReport(Path path) throws IOException {
        String fileName = path.getFileName().toString();
        String evalType = resolveEvalType(fileName);
        String content = Files.readString(path, StandardCharsets.UTF_8);
        Map<String, String> metrics = parseMetrics(content);
        LocalDateTime mtime = LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()), ZoneId.systemDefault());
        String metricsJson = writeJson(metrics);
        String preview = preview(content);
        String status = deriveStatus(metrics);
        Double successRate = firstMetricNumber(metrics, "success", "pass rate", "Success rate", "Pass rate");
        Double p50 = firstMetricNumber(metrics, "p50", "P50");
        Double p95 = firstMetricNumber(metrics, "p95", "P95");

        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO ai_eval_run(eval_type,report_file,status,success_rate,p50_ms,p95_ms,metrics_json,preview,report_mtime)
                VALUES (?,?,?,?,?,?,?::jsonb,?,?)
                ON CONFLICT(report_file) DO UPDATE SET
                    eval_type=EXCLUDED.eval_type,
                    status=EXCLUDED.status,
                    success_rate=EXCLUDED.success_rate,
                    p50_ms=EXCLUDED.p50_ms,
                    p95_ms=EXCLUDED.p95_ms,
                    metrics_json=EXCLUDED.metrics_json,
                    preview=EXCLUDED.preview,
                    report_mtime=EXCLUDED.report_mtime,
                    updated_time=CURRENT_TIMESTAMP
                RETURNING id
                """, Long.class, evalType, fileName, status, successRate, p50, p95, metricsJson, preview, Timestamp.valueOf(mtime));
        if (id == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM ai_eval_metric WHERE eval_run_id = ?", id);
        for (Map.Entry<String, String> entry : metrics.entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO ai_eval_metric(eval_run_id,metric_name,metric_value,metric_numeric)
                    VALUES (?,?,?,?)
                    """, id, entry.getKey(), entry.getValue(), parseNumber(entry.getValue()));
        }
    }

    private List<EvalReportSummaryVO> queryReports(int limit) {
        return jdbcTemplate.query("""
                SELECT id,eval_type,report_file,metrics_json::text,preview,report_mtime,updated_time
                FROM ai_eval_run
                ORDER BY updated_time DESC
                LIMIT ?
                """, (rs, rowNum) -> {
            EvalReportSummaryVO vo = new EvalReportSummaryVO();
            vo.setFileName(rs.getString("report_file"));
            vo.setEvalType(rs.getString("eval_type"));
            vo.setSizeBytes(0L);
            vo.setLastModifiedTime(toLocalDateTime(rs.getTimestamp("report_mtime")));
            vo.setMetrics(readMap(rs.getString("metrics_json")));
            vo.setPreview(rs.getString("preview"));
            return vo;
        }, limit);
    }

    private Integer countReports() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_eval_run", Integer.class);
        return count == null ? 0 : count;
    }

    private String resolveEvalType(String fileName) {
        Matcher matcher = REPORT_PATTERN.matcher(fileName);
        return matcher.matches() ? matcher.group(1) : "unknown";
    }

    private Map<String, String> parseMetrics(String content) {
        Map<String, String> metrics = new LinkedHashMap<>();
        String[] lines = content.split("\\R");
        boolean inSummary = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equalsIgnoreCase("## Summary")) {
                inSummary = true;
                continue;
            }
            if (inSummary && trimmed.startsWith("## ")) {
                break;
            }
            if (!inSummary || !trimmed.startsWith("-")) {
                continue;
            }
            addMetric(metrics, trimmed);
        }
        if (metrics.isEmpty()) {
            for (String line : lines) {
                addMetric(metrics, line.trim());
                if (metrics.size() >= 12) {
                    break;
                }
            }
        }
        return metrics;
    }

    private void addMetric(Map<String, String> metrics, String line) {
        Matcher matcher = METRIC_PATTERN.matcher(line);
        if (matcher.matches()) {
            metrics.put(matcher.group(1).trim(), matcher.group(2).trim());
        }
    }

    private String deriveStatus(Map<String, String> metrics) {
        for (Map.Entry<String, String> entry : metrics.entrySet()) {
            String key = entry.getKey().toLowerCase();
            Double number = parseNumber(entry.getValue());
            if ((key.contains("failed") || key.contains("risky") || key.contains("error"))
                    && number != null && number > 0) {
                return "RISK";
            }
        }
        return "OK";
    }

    private Double firstMetricNumber(Map<String, String> metrics, String... keys) {
        for (Map.Entry<String, String> entry : metrics.entrySet()) {
            String key = entry.getKey().toLowerCase();
            for (String expected : keys) {
                if (key.contains(expected.toLowerCase())) {
                    return parseNumber(entry.getValue());
                }
            }
        }
        return null;
    }

    private Double parseNumber(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(value.replace(",", ""));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String preview(String content) {
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
