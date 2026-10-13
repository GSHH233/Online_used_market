package org.example.online_used_marketplace.modules.ai.memory;

import java.util.List;

public record L2MemoryRecallQuery(
        String userId,
        String sessionId,
        String query,
        List<String> memoryTypes,
        int topK,
        double relevanceThreshold,
        int candidateLimit,
        boolean forceLexicalFallback) {
}
