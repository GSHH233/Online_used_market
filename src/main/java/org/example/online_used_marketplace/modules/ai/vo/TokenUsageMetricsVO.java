package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class TokenUsageMetricsVO {

    private int requestCount;
    private int successCount;
    private int actualUsageCount;
    private int estimatedUsageCount;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long trimmedTokens;
    private double averageTokens;
    private double p95Tokens;
    private double budgetCompliantRate;
    private Map<String, Long> sceneTokens = new LinkedHashMap<>();
}
