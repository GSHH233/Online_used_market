package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeGapMetricsVO {
    private LocalDateTime from;
    private LocalDateTime to;
    private Long totalGaps;
    private Long resolvedGaps;
    private Long completedGovernanceGaps;
    private Long recurrentGaps;
    private Long openGaps;
    private Long ragChatTurns;
    private Long refusedTurns;
    private Double gapResolutionRate;
    private Double recurrenceRate;
    private Double averageOpenGapAgeMs;
    private Double refusalRate;
}
