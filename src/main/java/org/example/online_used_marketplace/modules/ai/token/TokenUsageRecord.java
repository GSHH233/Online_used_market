package org.example.online_used_marketplace.modules.ai.token;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TokenUsageRecord {

    private Long id;
    private String traceId;
    private TokenScene scene;
    private String model;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private int inputBudget;
    private int outputBudget;
    private int originalPromptTokens;
    private int trimmedTokens;
    private boolean usageEstimated;
    private int attempts;
    private long elapsedMs;
    private String status;
    private String errorMessage;
    private LocalDateTime createdTime;
}
