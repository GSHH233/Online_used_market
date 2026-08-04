package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.token.TokenBudgetResult;
import org.example.online_used_marketplace.modules.ai.token.TokenPromptSection;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;

import java.util.List;

public interface TokenGovernanceService {

    TokenBudgetResult assemble(TokenScene scene, List<TokenPromptSection> sections);

    TokenBudgetResult fit(TokenScene scene, String prompt);
}
