package org.example.online_used_marketplace.modules.ai.memory;

public record L2MemoryDeleteCommand(
        String userId,
        String memoryType,
        String sourceEventId,
        String scopeType,
        String scopeKey,
        String slotKey) {
}
