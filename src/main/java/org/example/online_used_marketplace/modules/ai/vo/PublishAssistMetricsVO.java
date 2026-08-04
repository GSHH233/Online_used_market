package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PublishAssistMetricsVO {

    private LocalDateTime from;

    private LocalDateTime to;

    private Long generatedCount;

    private Long feedbackCount;

    private Long appliedCount;

    private Long partialCount;

    private Long ignoredCount;

    private Long flaggedWithOutcomeCount;

    private Long confirmedRiskCount;

    private Long passedRiskCount;

    private Double feedbackCoverage;

    private Double adoptionRate;

    private Double fullAdoptionRate;

    private Double riskPrecision;

    private Double falsePositiveRate;
}
