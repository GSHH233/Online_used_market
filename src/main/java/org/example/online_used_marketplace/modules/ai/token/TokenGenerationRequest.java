package org.example.online_used_marketplace.modules.ai.token;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenGenerationRequest {

    private TokenScene scene;
    private String traceId;
    private String systemPrompt;
    private String prompt;
    private TokenBudgetResult budgetResult;
}
