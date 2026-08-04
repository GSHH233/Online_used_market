package org.example.online_used_marketplace.modules.ai.token;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TokenBudgetResult {

    private TokenScene scene;
    private String prompt;
    private int inputBudget;
    private int outputBudget;
    private int originalPromptTokens;
    private int promptTokens;
    private int trimmedTokens;
    private boolean trimmed;
    private List<String> trimmedSections;
}
