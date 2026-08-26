package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class DefaultAiopsRemediationPolicy implements AiopsRemediationPolicy {

    public static final String REPAIR_KNOWLEDGE_DOC = "REPAIR_KNOWLEDGE_DOC";
    public static final String REPAIR_KNOWLEDGE_BATCH = "REPAIR_KNOWLEDGE_BATCH";
    public static final String ROLLBACK_KNOWLEDGE_REVISION = "ROLLBACK_KNOWLEDGE_REVISION";
    public static final String RESET_DEMO_SERVICE_FAULT = "RESET_DEMO_SERVICE_FAULT";

    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            REPAIR_KNOWLEDGE_DOC,
            REPAIR_KNOWLEDGE_BATCH,
            ROLLBACK_KNOWLEDGE_REVISION,
            RESET_DEMO_SERVICE_FAULT);

    @Override
    public RemediationRecommendation recommend(AlertInvestigationContext context, Diagnosis diagnosis) {
        if (context == null || diagnosis == null || diagnosis.getEvidence() == null
                || diagnosis.getEvidence().isEmpty()) {
            return RemediationRecommendation.empty("no evidence; remediation is not recommended");
        }
        Map<String, String> labels = context.trustedLabelsView();
        String action = normalize(labels.get("runbookAction"));
        if (!ALLOWED_ACTIONS.contains(action)) {
            return RemediationRecommendation.empty("trusted labels did not select a supported runbook");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        switch (action) {
            case REPAIR_KNOWLEDGE_DOC -> {
                Long docId = positiveLong(firstNonBlank(labels.get("docId"), labels.get("targetDocId")));
                if (docId == null) {
                    return RemediationRecommendation.empty("doc repair requires a trusted docId");
                }
                params.put("docId", docId);
                params.put("forceReindex", booleanLabel(labels.get("forceReindex"), false));
            }
            case REPAIR_KNOWLEDGE_BATCH -> {
                params.put("sampleDocs", boundedInt(labels.get("sampleDocs"), 20, 1, 100));
                params.put("perDocLimit", boundedInt(labels.get("perDocLimit"), 200, 1, 500));
                params.put("forceReindex", booleanLabel(labels.get("forceReindex"), false));
            }
            case ROLLBACK_KNOWLEDGE_REVISION -> {
                Long targetDocId = positiveLong(labels.get("targetDocId"));
                if (targetDocId == null) {
                    return RemediationRecommendation.empty("revision rollback requires a trusted targetDocId");
                }
                params.put("targetDocId", targetDocId);
            }
            case RESET_DEMO_SERVICE_FAULT -> {
                String service = labels.get("service");
                String profile = labels.get("faultProfile");
                if (!"marketplace-demo-worker".equals(service)
                        || !booleanLabel(labels.get("demoEnabled"), false)
                        || profile == null || !profile.matches("[A-Za-z0-9_.:-]{1,64}")) {
                    return RemediationRecommendation.empty("demo reset requires the enabled demo service and trusted profile");
                }
                params.put("service", service);
                params.put("faultProfile", profile);
            }
            default -> {
                return RemediationRecommendation.empty("unsupported runbook");
            }
        }
        return RemediationRecommendation.builder()
                .actionCode(action)
                .params(params)
                .reason("selected from trusted labels after evidence-backed diagnosis; ADMIN approval required")
                .approvalRequired(true)
                .build();
    }

    public Set<String> allowedActionCodes() {
        return ALLOWED_ACTIONS;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private Long positiveLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int boundedInt(String value, int fallback, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean booleanLabel(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        return fallback;
    }
}
