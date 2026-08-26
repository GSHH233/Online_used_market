package org.example.online_used_marketplace.modules.ai.aiops.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.modules.ai.aiops.collaboration.AiopsCollaborationEngine;
import org.example.online_used_marketplace.modules.ai.aiops.collaboration.AiopsCollaborationResult;
import org.example.online_used_marketplace.modules.ai.aiops.collaboration.AlertInvestigationContext;
import org.example.online_used_marketplace.modules.ai.aiops.collaboration.RemediationRecommendation;
import org.example.online_used_marketplace.modules.ai.alert.AiopsAlertInvestigationHandler;
import org.example.online_used_marketplace.modules.ai.alert.AiopsAlertInvestigationMessage;
import org.example.online_used_marketplace.modules.ai.config.AiopsDemoProperties;
import org.example.online_used_marketplace.modules.ai.dto.ControlledOperationPlanRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.service.ControlledOperationService;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;
import org.example.online_used_marketplace.modules.ai.vo.ControlledOperationVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AiopsAlertCollaborationHandler implements AiopsAlertInvestigationHandler {

    private static final List<String> TERMINAL_STATUSES = List.of(
            "RESOLVED", "REJECTED", "ESCALATED", "SOURCE_RECOVERED");

    private final AiopsCollaborationEngine collaborationEngine;
    private final AiopsEventService aiopsEventService;
    private final ControlledOperationService controlledOperationService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiopsDemoProperties demoProperties;
    private final double minPlanConfidence;

    public AiopsAlertCollaborationHandler(
            AiopsCollaborationEngine collaborationEngine,
            AiopsEventService aiopsEventService,
            ControlledOperationService controlledOperationService,
            @Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AiopsDemoProperties demoProperties,
            @Value("${ai.aiops.collaboration.min-plan-confidence:0.40}") double minPlanConfidence) {
        this.collaborationEngine = collaborationEngine;
        this.aiopsEventService = aiopsEventService;
        this.controlledOperationService = controlledOperationService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.demoProperties = demoProperties;
        this.minPlanConfidence = Math.max(0D, Math.min(1D, minPlanConfidence));
    }

    @Override
    public void investigate(AiopsAlertInvestigationMessage message) {
        if (message == null || message.getEventId() == null || message.getEventId() <= 0) {
            throw new IllegalArgumentException("AIOps investigation message is invalid");
        }
        Long eventId = message.getEventId();
        if (!claim(eventId)) {
            String status = normalize(aiopsEventService.get(eventId).getDiagnosisStatus());
            if (List.of("RUNNING", "COMPLETED", "DEGRADED").contains(status)) {
                return;
            }
            throw new IllegalStateException("AIOps event cannot enter diagnosis: " + status);
        }

        try {
            AiopsEventVO event = aiopsEventService.get(eventId);
            AiopsCollaborationResult result = collaborationEngine.investigate(buildContext(event, message));
            String diagnosisJson = diagnosisJson(result);
            String diagnosisHash = sha256(diagnosisJson);
            String diagnosisStatus = result.getDegradedSpecialists() == null
                    || result.getDegradedSpecialists().isEmpty() ? "COMPLETED" : "DEGRADED";
            persistDiagnosis(eventId, diagnosisStatus, diagnosisJson);

            AiopsEventVO latest = aiopsEventService.get(eventId);
            if (TERMINAL_STATUSES.contains(normalize(latest.getStatus()))) {
                return;
            }
            RemediationRecommendation recommendation = result.getRemediation();
            if (!eligibleForPlan(result, recommendation)) {
                return;
            }
            ControlledOperationVO operation = createPlan(event, result, recommendation, diagnosisHash);
            jdbcTemplate.update("""
                    UPDATE aiops_event
                    SET operation_id=?, updated_time=CURRENT_TIMESTAMP
                    WHERE id=? AND operation_id IS NULL
                    """, operation.getOperationId(), eventId);
        } catch (RuntimeException ex) {
            markFailed(eventId, ex);
            throw ex;
        }
    }

    private boolean claim(Long eventId) {
        return jdbcTemplate.update("""
                UPDATE aiops_event
                SET diagnosis_status='RUNNING', updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                  AND (
                    diagnosis_status IN ('PENDING','FAILED','')
                    OR (diagnosis_status='RUNNING' AND updated_time < CURRENT_TIMESTAMP - INTERVAL '5 minutes')
                  )
                """, eventId) == 1;
    }

    private AlertInvestigationContext buildContext(AiopsEventVO event,
                                                    AiopsAlertInvestigationMessage message) {
        Map<String, Object> metadata = readMap(event.getMetadataJson());
        Map<String, String> labels = stringMap(metadata.get("labels"));
        Map<String, String> annotations = stringMap(metadata.get("annotations"));
        String service = safeService(firstNonBlank(string(metadata.get("service")),
                labels.get("service"), labels.get("job")));
        String alertName = firstNonBlank(labels.get("alertname"), event.getTitle());
        Map<String, String> trustedLabels = new LinkedHashMap<>();
        if (isAllowedDemoService(service) && isDemoAlert(alertName)) {
            trustedLabels.put("service", service);
            trustedLabels.put("runbookAction", "RESET_DEMO_SERVICE_FAULT");
            trustedLabels.put("demoEnabled", String.valueOf(demoProperties.isEnabled()));
            trustedLabels.put("faultProfile", "controlled-demo-fault");
        } else if (StringUtils.hasText(service)) {
            trustedLabels.put("service", service);
        }

        AlertInvestigationContext context = AlertInvestigationContext.fromExternal(
                String.valueOf(event.getId()), alertName, service, event.getTraceId(), labels, annotations);
        context.setFingerprint(event.getFingerprint());
        context.setGroupKey(event.getCorrelationKey());
        context.setSource(event.getSource());
        context.setSourceStartedAt(toInstant(event.getSourceStartedAt()));
        context.setObservedAt(toInstant(event.getLastSeenAt()));
        context.setTrustedLabels(trustedLabels);
        context.setExternalAttributes(Map.of(
                "deliveryId", message.getDeliveryId(),
                "payloadHash", safe(message.getPayloadHash(), 64)));
        return context;
    }

    private boolean eligibleForPlan(AiopsCollaborationResult result,
                                    RemediationRecommendation recommendation) {
        return result != null
                && result.getDiagnosis() != null
                && result.getDiagnosis().getEvidence() != null
                && !result.getDiagnosis().getEvidence().isEmpty()
                && result.getDiagnosis().getConfidence() >= minPlanConfidence
                && recommendation != null
                && StringUtils.hasText(recommendation.getActionCode());
    }

    private ControlledOperationVO createPlan(AiopsEventVO event,
                                             AiopsCollaborationResult result,
                                             RemediationRecommendation recommendation,
                                             String diagnosisHash) {
        ControlledOperationPlanRequestDTO request = new ControlledOperationPlanRequestDTO();
        request.setActionCode(recommendation.getActionCode());
        request.setParams(recommendation.getParams());
        request.setTraceId(event.getTraceId());
        request.setSourceEventId(event.getId());
        request.setDiagnosisHash(diagnosisHash);
        request.setReason("evidence-backed AIOps diagnosis; confidence="
                + result.getDiagnosis().getConfidence() + "; ADMIN approval required");
        return controlledOperationService.createPlan(request);
    }

    private void persistDiagnosis(Long eventId, String diagnosisStatus, String diagnosisJson) {
        jdbcTemplate.update("""
                UPDATE aiops_event
                SET diagnosis_status=?, diagnosis_json=?::jsonb,
                    status=CASE WHEN status='OPEN' THEN 'DIAGNOSED' ELSE status END,
                    updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, diagnosisStatus, diagnosisJson, eventId);
    }

    private void markFailed(Long eventId, RuntimeException ex) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("error", safe(ex.getMessage(), 512));
        failure.put("failedAt", LocalDateTime.now().toString());
        jdbcTemplate.update("""
                UPDATE aiops_event
                SET diagnosis_status='FAILED', diagnosis_json=?::jsonb, updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, writeJson(failure), eventId);
    }

    private String diagnosisJson(AiopsCollaborationResult result) {
        Map<String, Object> persisted = new LinkedHashMap<>();
        persisted.put("diagnosis", result == null ? null : result.getDiagnosis());
        persisted.put("specialists", result == null ? List.of() : result.getSpecialistResults());
        persisted.put("degradedSpecialists", result == null ? List.of() : result.getDegradedSpecialists());
        persisted.put("elapsedMs", result == null ? 0L : result.getElapsedMs());
        persisted.put("externalInstructionsTrusted", false);
        return writeJson(persisted);
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("AIOps event metadata cannot be parsed", ex);
        }
    }

    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return new LinkedHashMap<>();
        }
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(String.valueOf(key), String.valueOf(item));
            }
        });
        return result;
    }

    private boolean isAllowedDemoService(String service) {
        if (!demoProperties.isEnabled() || !StringUtils.hasText(service)) {
            return false;
        }
        List<String> allowed = demoProperties.getAllowedServices();
        return allowed != null && allowed.stream()
                .filter(StringUtils::hasText)
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .anyMatch(service::equals);
    }

    private boolean isDemoAlert(String alertName) {
        String text = safe(alertName, 255).toLowerCase(Locale.ROOT);
        return text.contains("aiopsdemoservice")
                || text.contains("demo_service")
                || text.contains("demo service");
    }

    private String safeService(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9][a-z0-9._-]{0,63}") ? normalized : "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private java.time.Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("AIOps diagnosis cannot be serialized", ex);
        }
    }

    private String safe(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
