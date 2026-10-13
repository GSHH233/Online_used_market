package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.dto.LogQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.LogToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class LogSpecialistAgent implements AiopsSpecialistAgent {

    private final LogToolService logToolService;

    public LogSpecialistAgent(LogToolService logToolService) {
        this.logToolService = logToolService;
    }

    @Override
    public SpecialistType type() {
        return SpecialistType.LOG;
    }

    @Override
    public SpecialistResult investigate(AlertInvestigationContext context) {
        if (context == null || !safeTraceId(context.getTraceId())) {
            return SpecialistResult.degraded(type(), "trace_id", "structured traceId is required for log lookup");
        }
        if (logToolService == null) {
            return SpecialistResult.degraded(type(), "log_source_unavailable", "Log service unavailable");
        }
        String traceId = context.getTraceId().trim();
        String service = safeService(context.getService());
        try {
            LogQueryRequestDTO request = new LogQueryRequestDTO();
            request.setTraceId(traceId);
            request.setService(service);
            request.setWindowMinutes(context.effectiveWindowMinutes());
            request.setLimit(100);
            ToolQueryResultVO result = logToolService.queryByTraceId(request);
            if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
                return SpecialistResult.builder()
                        .specialist(type())
                        .status(SpecialistStatus.DEGRADED)
                        .missingEvidence(List.of("log_result"))
                        .errorMessage("log source returned no observations")
                        .build();
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("resultCount", result.getRows().size());
            attributes.put("total", result.getTotal());
            attributes.put("traceId", traceId);
            attributes.put("service", service);
            attributes.put("windowMinutes", context.effectiveWindowMinutes());
            attributes.put("findingCode", findingCode(result));
            String target = "traceId=" + traceId + ",service=" + service
                    + ",windowMinutes=" + context.effectiveWindowMinutes();
            Evidence evidence = Evidence.observed(
                    EvidenceIds.forSpecialist(type(), context),
                    type(),
                    "log_query",
                    target,
                    "Structured trace log lookup returned " + result.getRows().size() + " observation(s)",
                    context.effectiveObservedAt(),
                    attributes);
            return SpecialistResult.completed(type(), List.of(evidence));
        } catch (Exception ex) {
            log.debug("log specialist degraded: {}", ex.getMessage());
            return SpecialistResult.degraded(type(), "log_result", safeMessage(ex));
        }
    }

    private boolean safeTraceId(String value) {
        return StringUtils.hasText(value) && value.length() <= 128
                && value.matches("[A-Za-z0-9._:-]+");
    }

    private String safeService(String value) {
        if (value == null || value.isBlank() || value.length() > 100
                || !value.matches("[A-Za-z0-9_.:-]+")) {
            return "";
        }
        return value.trim();
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }

    private String findingCode(ToolQueryResultVO result) {
        String text = String.valueOf(result.getSummary()) + " " + String.valueOf(result.getRows());
        String normalized = text.toLowerCase();
        if (normalized.contains("database") || normalized.contains("jdbc")
                || normalized.contains("sql") || normalized.contains("connection timeout")) {
            return "DB_TIMEOUT";
        }
        if (normalized.contains("connection refused") || normalized.contains("unavailable")) {
            return "CONNECTION_REFUSED";
        }
        if (normalized.contains("outofmemory") || normalized.contains("oom")) {
            return "OOM";
        }
        return "APP_ERROR";
    }
}
