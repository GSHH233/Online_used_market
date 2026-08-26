package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Diagnosis {

    @Builder.Default
    private List<Evidence> evidence = new ArrayList<>();

    @Builder.Default
    private List<String> missingEvidence = new ArrayList<>();

    @Builder.Default
    private List<Hypothesis> rootCauseHypotheses = new ArrayList<>();

    private double confidence;

    private String recommendedActionCode;

    @Builder.Default
    private Map<String, Object> recommendedParams = new LinkedHashMap<>();

    private String recommendationReason;

    private boolean degraded;
}
