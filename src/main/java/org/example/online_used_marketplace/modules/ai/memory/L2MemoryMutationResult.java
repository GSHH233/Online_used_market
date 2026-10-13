package org.example.online_used_marketplace.modules.ai.memory;

public record L2MemoryMutationResult(
        Long recordId,
        String disposition,
        String status,
        long revision) {
}
