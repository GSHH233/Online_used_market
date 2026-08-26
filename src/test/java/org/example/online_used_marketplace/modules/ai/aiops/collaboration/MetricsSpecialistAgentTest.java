package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import org.example.online_used_marketplace.modules.ai.dto.PrometheusQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.PrometheusToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsSpecialistAgentTest {

    @Test
    void usesServerTemplateAndIgnoresExternalPromQlAnnotation() {
        CapturingPrometheus service = new CapturingPrometheus();
        MetricsSpecialistAgent agent = new MetricsSpecialistAgent(service);
        AlertInvestigationContext context = AlertInvestigationContext.fromExternal(
                "event-1",
                "HighLatency",
                "marketplace-api",
                "trace-1",
                Map.of("query", "up{job=~\\\".*\\\"}", "service", "marketplace-api"),
                Map.of("promql", "sum(rate(secrets_leaked_total[5m]))"));

        SpecialistResult result = agent.investigate(context);

        assertTrue(result.getEvidence().size() == 1);
        assertTrue(service.request.getQuery().contains("histogram_quantile"));
        assertFalse(service.request.getQuery().contains("secrets_leaked_total"));
        assertFalse(service.request.getQuery().contains("up{job=~"));
        assertTrue("INTERNAL".equals(service.request.getMode()));
    }

    @Test
    void usesDemoHealthTemplateForTrustedDemoService() {
        CapturingPrometheus service = new CapturingPrometheus();
        MetricsSpecialistAgent agent = new MetricsSpecialistAgent(service);
        AlertInvestigationContext context = AlertInvestigationContext.builder()
                .alertName("DemoServiceDown")
                .service("attacker-supplied-service")
                .trustedLabels(Map.of("service", "marketplace-demo-worker"))
                .build();

        agent.investigate(context);

        assertTrue(service.request.getQuery().contains("aiops_demo_service_health"));
        assertTrue(service.request.getQuery().contains("service=\"marketplace-demo-worker\""));
        assertFalse(service.request.getQuery().contains("attacker-supplied-service"));
    }

    private static final class CapturingPrometheus implements PrometheusToolService {

        private PrometheusQueryRequestDTO request;

        @Override
        public ToolQueryResultVO query(PrometheusQueryRequestDTO request) {
            this.request = request;
            ToolQueryResultVO result = new ToolQueryResultVO();
            result.setRows(List.of(Map.of("value", "1")));
            result.setTotal(1L);
            return result;
        }
    }
}
