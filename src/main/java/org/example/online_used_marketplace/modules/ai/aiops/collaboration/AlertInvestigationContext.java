package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Self-contained context passed from the alert adapter to the collaboration engine.
 * The adapter is responsible for normalizing trustedLabels. Raw annotations remain
 * observable only and are never used for control decisions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertInvestigationContext {

    private String eventId;

    private String alertName;

    private String service;

    private String traceId;

    private String fingerprint;

    private String groupKey;

    private String source;

    private Instant sourceStartedAt;

    private Instant observedAt;

    @Builder.Default
    private Integer windowMinutes = 30;

    @Builder.Default
    private Map<String, String> labels = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, String> trustedLabels = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, UntrustedAnnotation> annotations = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> externalAttributes = new LinkedHashMap<>();

    public static AlertInvestigationContext fromExternal(String eventId,
                                                          String alertName,
                                                          String service,
                                                          String traceId,
                                                          Map<String, String> labels,
                                                          Map<String, String> annotations) {
        Map<String, UntrustedAnnotation> untrusted = new LinkedHashMap<>();
        if (annotations != null) {
            annotations.forEach((key, value) -> untrusted.put(key,
                    UntrustedAnnotation.fromExternal(key, value)));
        }
        return AlertInvestigationContext.builder()
                .eventId(eventId)
                .alertName(alertName)
                .service(service)
                .traceId(traceId)
                .labels(copy(labels))
                .annotations(untrusted)
                .observedAt(Instant.now())
                .build();
    }

    public Instant effectiveObservedAt() {
        return observedAt == null ? Instant.now() : observedAt;
    }

    public int effectiveWindowMinutes() {
        if (windowMinutes == null) {
            return 30;
        }
        return Math.max(1, Math.min(windowMinutes, 1440));
    }

    public String trustedLabel(String key) {
        if (trustedLabels == null || key == null) {
            return null;
        }
        return trustedLabels.get(key);
    }

    public Map<String, String> trustedLabelsView() {
        return trustedLabels == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(trustedLabels);
    }

    public void normalizeExternalAnnotations() {
        Map<String, UntrustedAnnotation> normalized = new LinkedHashMap<>();
        if (annotations != null) {
            annotations.forEach((key, value) -> {
                if (value == null) {
                    normalized.put(key, UntrustedAnnotation.fromExternal(key, null));
                } else {
                    normalized.put(key, UntrustedAnnotation.builder()
                            .key(key)
                            .value(value.getValue())
                            .trustLevel(TrustLevel.UNTRUSTED)
                            .build());
                }
            });
        }
        annotations = normalized;
    }

    /**
     * Provides the optional model enhancer with a control-plane-only view.
     * Raw labels, annotations and external attributes are intentionally removed.
     */
    public AlertInvestigationContext controlPlaneView() {
        return AlertInvestigationContext.builder()
                .eventId(eventId)
                .alertName(alertName)
                .service(service)
                .traceId(traceId)
                .fingerprint(fingerprint)
                .groupKey(groupKey)
                .source(source)
                .sourceStartedAt(sourceStartedAt)
                .observedAt(observedAt)
                .windowMinutes(effectiveWindowMinutes())
                .trustedLabels(copy(trustedLabels))
                .labels(new LinkedHashMap<>())
                .annotations(new LinkedHashMap<>())
                .externalAttributes(new LinkedHashMap<>())
                .build();
    }

    private static Map<String, String> copy(Map<String, String> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
