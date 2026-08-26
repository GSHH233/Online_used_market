package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Evidence {

    private String evidenceId;

    private SpecialistType source;

    private String tool;

    private String target;

    private String summary;

    private Instant observedAt;

    @Builder.Default
    private EvidenceStatus status = EvidenceStatus.OBSERVED;

    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();

    public static Evidence observed(String evidenceId,
                                    SpecialistType source,
                                    String tool,
                                    String target,
                                    String summary,
                                    Instant observedAt,
                                    Map<String, Object> attributes) {
        return Evidence.builder()
                .evidenceId(evidenceId)
                .source(source)
                .tool(tool)
                .target(target)
                .summary(summary)
                .observedAt(observedAt)
                .status(EvidenceStatus.OBSERVED)
                .attributes(attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes))
                .build();
    }

    public static Evidence missing(String evidenceId,
                                   SpecialistType source,
                                   String tool,
                                   String target,
                                   String reason,
                                   Instant observedAt) {
        return Evidence.builder()
                .evidenceId(evidenceId)
                .source(source)
                .tool(tool)
                .target(target)
                .summary(reason)
                .observedAt(observedAt)
                .status(EvidenceStatus.MISSING)
                .build();
    }
}
