package org.example.online_used_marketplace.modules.ai.token;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenGenerationResult {

    private String content;
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
}
