package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.PrometheusToolProperties;
import org.example.online_used_marketplace.modules.ai.dto.PrometheusQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.PrometheusToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrometheusToolServiceImpl implements PrometheusToolService {

    private final PrometheusToolProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public ToolQueryResultVO query(PrometheusQueryRequestDTO request) {
        long startMs = System.currentTimeMillis();
        int limit = normalizeLimit(request == null ? null : request.getLimit());
        String query = request == null ? null : request.getQuery();
        if (!properties.isEnabled()) {
            return empty(query, limit, "Prometheus工具未启用", startMs);
        }
        if (!StringUtils.hasText(query)) {
            return empty(query, limit, "PromQL为空，未执行查询", startMs);
        }
        try {
            URI uri = buildUri(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofMillis(Math.max(100, properties.getTimeoutMs())))
                    .build();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(100, properties.getTimeoutMs())))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Prometheus HTTP错误: " + response.statusCode());
            }
            return parseResponse(query, limit, response.body(), startMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Prometheus查询被中断: " + errorMessage(ex), ex);
        } catch (Exception ex) {
            log.warn("prometheus query failed, query={}, message={}", query, ex.getMessage());
            throw new IllegalStateException("Prometheus查询失败: " + errorMessage(ex), ex);
        }
    }

    private String errorMessage(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getMessage();
        if (StringUtils.hasText(message)) {
            return message;
        }
        Throwable cause = ex.getCause();
        if (cause != null && StringUtils.hasText(cause.getMessage())) {
            return cause.getMessage();
        }
        return ex.getClass().getSimpleName();
    }

    private URI buildUri(PrometheusQueryRequestDTO request) {
        String baseUrl = trimTrailingSlash(properties.getBaseUrl());
        boolean range = "range".equalsIgnoreCase(request.getQueryType()) || StringUtils.hasText(request.getStart());
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + (range ? "/api/v1/query_range" : "/api/v1/query"))
                .queryParam("query", request.getQuery());
        if (range) {
            RangeWindow window = buildRangeWindow(request);
            builder.queryParam("start", window.start());
            builder.queryParam("end", window.end());
            builder.queryParam("step", StringUtils.hasText(request.getStep()) ? request.getStep() : properties.getDefaultStep());
        } else if (StringUtils.hasText(request.getTime())) {
            builder.queryParam("time", request.getTime());
        }
        return builder.build().encode().toUri();
    }

    private RangeWindow buildRangeWindow(PrometheusQueryRequestDTO request) {
        String end = StringUtils.hasText(request.getEnd()) ? request.getEnd() : String.valueOf(Instant.now().getEpochSecond());
        String start = request.getStart();
        if (!StringUtils.hasText(start)) {
            long endEpoch = parseEpochSeconds(end, Instant.now().getEpochSecond());
            start = String.valueOf(endEpoch - Math.max(1, properties.getDefaultRangeMinutes()) * 60L);
        }
        return new RangeWindow(start, end);
    }

    private long parseEpochSeconds(String text, long defaultValue) {
        try {
            return Long.parseLong(text);
        } catch (Exception ignore) {
            return defaultValue;
        }
    }

    private ToolQueryResultVO parseResponse(String query, int limit, String body, long startMs) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String status = root.path("status").asText();
        if (!"success".equalsIgnoreCase(status)) {
            String error = root.path("error").asText(root.path("errorType").asText("unknown"));
            return empty(query, limit, "Prometheus返回失败: " + error, startMs);
        }
        JsonNode data = root.path("data");
        String resultType = data.path("resultType").asText("unknown");
        JsonNode result = data.path("result");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (result.isArray()) {
            for (JsonNode item : result) {
                if (rows.size() >= limit) {
                    break;
                }
                rows.add(toRow(resultType, item));
            }
        } else if (!result.isMissingNode() && !result.isNull()) {
            rows.add(Map.of("resultType", resultType, "value", result.toString()));
        }

        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool("prometheus_query");
        vo.setTarget(query);
        vo.setLimit(limit);
        vo.setTotal(result.isArray() ? (long) result.size() : (long) rows.size());
        vo.setTruncated(result.isArray() && result.size() > limit);
        vo.setElapsedMs(System.currentTimeMillis() - startMs);
        vo.setRows(rows);
        vo.setColumns(rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet()));
        vo.setSummary("Prometheus查询完成: resultType=" + resultType + ", rows=" + rows.size());
        return vo;
    }

    private Map<String, Object> toRow(String resultType, JsonNode item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resultType", resultType);
        row.put("metric", metricToMap(item.path("metric")));
        if (item.has("value")) {
            JsonNode value = item.path("value");
            row.put("timestamp", value.isArray() && value.size() > 0 ? value.get(0).asText() : null);
            row.put("value", value.isArray() && value.size() > 1 ? value.get(1).asText() : value.toString());
        }
        if (item.has("values")) {
            JsonNode values = item.path("values");
            List<Map<String, Object>> points = new ArrayList<>();
            int count = 0;
            for (JsonNode point : values) {
                if (count++ >= 10) {
                    break;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("timestamp", point.isArray() && point.size() > 0 ? point.get(0).asText() : null);
                one.put("value", point.isArray() && point.size() > 1 ? point.get(1).asText() : point.toString());
                points.add(one);
            }
            row.put("points", points);
            row.put("pointCount", values.size());
            row.put("pointsTruncated", values.size() > 10);
        }
        return row;
    }

    private Map<String, Object> metricToMap(JsonNode metric) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (metric == null || !metric.isObject()) {
            return map;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = metric.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            map.put(entry.getKey(), entry.getValue().asText());
        }
        return map;
    }

    private ToolQueryResultVO empty(String query, int limit, String summary, long startMs) {
        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool("prometheus_query");
        vo.setTarget(StringUtils.hasText(query) ? query : "EMPTY_QUERY");
        vo.setLimit(limit);
        vo.setTotal(0L);
        vo.setTruncated(false);
        vo.setElapsedMs(System.currentTimeMillis() - startMs);
        vo.setRows(List.of());
        vo.setColumns(List.of("resultType", "metric", "timestamp", "value"));
        vo.setSummary(summary);
        return vo;
    }

    private int normalizeLimit(Integer limit) {
        int value = limit == null || limit <= 0 ? properties.getDefaultLimit() : limit;
        return Math.max(1, Math.min(value, Math.max(1, properties.getMaxLimit())));
    }

    private String trimTrailingSlash(String baseUrl) {
        String text = StringUtils.hasText(baseUrl) ? baseUrl.trim() : "http://localhost:9090";
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private record RangeWindow(String start, String end) {
    }
}
