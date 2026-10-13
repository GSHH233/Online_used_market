package org.example.online_used_marketplace.modules.ai.memory;

public record L2MemoryWriteCommand(
        String userId,
        String sessionId,
        String memoryType,
        String content,
        Double importance,
        String metadataJson,
        String sourceEventId,
        String sourceType,
        String assertionStatus,
        String scopeType,
        String scopeKey,
        String slotKey) {
}
