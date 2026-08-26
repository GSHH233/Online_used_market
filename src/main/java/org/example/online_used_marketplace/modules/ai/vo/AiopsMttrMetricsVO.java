package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiopsMttrMetricsVO {

    private LocalDateTime from;

    private LocalDateTime to;

    private String source;

    private Long resolvedCount;

    private Long executedEventCount;

    private Long autoResolvedCount;

    private Long escalatedCount;

    private Double averageMttrMs;

    private Double p50MttrMs;

    private Double p95MttrMs;

    private Double autoRepairSuccessRate;

    private Double escalationRate;

    private Long mttdSampleCount;

    private Double averageMttdMs;

    private Double p50MttdMs;

    private Double p95MttdMs;

    private Long automationRepairCount;

    private Double averageAutomationRepairMs;

    private Double p50AutomationRepairMs;

    private Double p95AutomationRepairMs;

    private Long fullMttrCount;

    private Double averageFullMttrMs;

    private Double p50FullMttrMs;

    private Double p95FullMttrMs;
}
