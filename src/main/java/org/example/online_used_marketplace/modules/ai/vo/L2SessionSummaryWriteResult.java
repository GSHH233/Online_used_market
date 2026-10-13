package org.example.online_used_marketplace.modules.ai.vo;

public record L2SessionSummaryWriteResult(
        boolean accepted,
        long revision,
        L2MemoryHitVO currentMemory) {
}
