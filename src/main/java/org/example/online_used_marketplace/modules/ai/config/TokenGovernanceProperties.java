package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.token-governance")
public class TokenGovernanceProperties {

    private boolean enabled = true;
    private boolean persistEnabled = true;
    private int recentMemoryLimit = 500;
    private int defaultInputTokens = 4_000;
    private int defaultOutputTokens = 500;
    private int ragChatInputTokens = 6_000;
    private int ragChatOutputTokens = 600;
    private int agentFinalInputTokens = 4_000;
    private int agentFinalOutputTokens = 400;
    private int agentPlannerInputTokens = 2_000;
    private int agentPlannerOutputTokens = 350;
    private int sessionSummaryInputTokens = 3_000;
    private int sessionSummaryOutputTokens = 300;
    private int preferenceExtractionInputTokens = 1_200;
    private int preferenceExtractionOutputTokens = 250;
    private int queryRewriteInputTokens = 1_500;
    private int queryRewriteOutputTokens = 350;
    private int semanticChunkingInputTokens = 3_500;
    private int semanticChunkingOutputTokens = 500;
    private int publishOptimizeInputTokens = 2_500;
    private int publishOptimizeOutputTokens = 600;

    public int inputBudget(TokenScene scene) {
        return switch (scene == null ? TokenScene.GENERAL : scene) {
            case RAG_CHAT -> ragChatInputTokens;
            case AGENT_FINAL -> agentFinalInputTokens;
            case AGENT_PLANNER -> agentPlannerInputTokens;
            case SESSION_SUMMARY -> sessionSummaryInputTokens;
            case PREFERENCE_EXTRACTION -> preferenceExtractionInputTokens;
            case QUERY_REWRITE -> queryRewriteInputTokens;
            case SEMANTIC_CHUNKING -> semanticChunkingInputTokens;
            case PUBLISH_OPTIMIZE -> publishOptimizeInputTokens;
            default -> defaultInputTokens;
        };
    }

    public int outputBudget(TokenScene scene) {
        return switch (scene == null ? TokenScene.GENERAL : scene) {
            case RAG_CHAT -> ragChatOutputTokens;
            case AGENT_FINAL -> agentFinalOutputTokens;
            case AGENT_PLANNER -> agentPlannerOutputTokens;
            case SESSION_SUMMARY -> sessionSummaryOutputTokens;
            case PREFERENCE_EXTRACTION -> preferenceExtractionOutputTokens;
            case QUERY_REWRITE -> queryRewriteOutputTokens;
            case SEMANTIC_CHUNKING -> semanticChunkingOutputTokens;
            case PUBLISH_OPTIMIZE -> publishOptimizeOutputTokens;
            default -> defaultOutputTokens;
        };
    }
}
