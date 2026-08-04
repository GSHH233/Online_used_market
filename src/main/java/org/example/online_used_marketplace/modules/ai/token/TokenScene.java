package org.example.online_used_marketplace.modules.ai.token;

public enum TokenScene {
    GENERAL,
    RAG_CHAT,
    AGENT_FINAL,
    AGENT_PLANNER,
    SESSION_SUMMARY,
    PREFERENCE_EXTRACTION,
    QUERY_REWRITE,
    SEMANTIC_CHUNKING,
    PUBLISH_OPTIMIZE;

    public static TokenScene from(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return GENERAL;
        }
    }
}
