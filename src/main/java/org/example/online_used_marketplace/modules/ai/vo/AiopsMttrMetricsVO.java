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
}
