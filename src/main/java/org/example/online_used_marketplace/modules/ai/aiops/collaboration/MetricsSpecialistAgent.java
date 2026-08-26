package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.dto.PrometheusQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.PrometheusToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MetricsSpecialistAgent implements AiopsSpecialistAgent {

    private final PrometheusToolService prometheusToolService;

    public MetricsSpecialistAgent(PrometheusToolService prometheusToolService) {
        this.prometheusToolService = prometheusToolService;
    }

    @Override
    public SpecialistType type() {
        return SpecialistType.METRICS;
    }

    @Override
    public SpecialistResult investigate(AlertInvestigationContext context) {
        String evidenceId = EvidenceIds.forSpecialist(type(), context);
        String query = ServerSidePromQlTemplates.build(context);
        Instant observedAt = context == null ? Instant.now() : context.effectiveObservedAt();
        if (prometheusToolService == null) {
            return SpecialistResult.degraded(type(), "metrics_source_unavailable", "Prometheus service unavailable");
        }
        try {
            PrometheusQueryRequestDTO request = new PrometheusQueryRequestDTO();
            request.setQuery(query);
            request.setQueryType("instant");
            request.setLimit(20);
            request.setMode("INTERNAL");
            ToolQueryResultVO result = prometheusToolService.query(request);
            if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
                return SpecialistResult.builder()
                        .specialist(type())
                        .status(SpecialistStatus.DEGRADED)
                        .missingEvidence(List.of("metrics_result"))
                        .errorMessage("Prometheus returned no observations")
                        .build();
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("resultCount", result.getRows().size());
            attributes.put("total", result.getTotal());
            attributes.put("template", ServerSidePromQlTemplates.templateName(context));
            Evidence evidence = Evidence.observed(
                    evidenceId,
                    type(),
                    "prometheus_query",
                    query,
                    "Prometheus server template returned " + result.getRows().size() + " observation(s)",
                    observedAt,
                    attributes);
            return SpecialistResult.completed(type(), List.of(evidence));
        } catch (Exception ex) {
            log.debug("metrics specialist degraded: {}", ex.getMessage());
            return SpecialistResult.degraded(type(), "metrics_result", safeMessage(ex));
        }
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }
}
