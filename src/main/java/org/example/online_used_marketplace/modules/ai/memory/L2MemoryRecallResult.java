package org.example.online_used_marketplace.modules.ai.memory;

import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;

import java.util.List;

public record L2MemoryRecallResult(
        List<L2MemoryHitVO> hits,
        long eligibleCandidateCount,
        int evaluatedCandidateCount,
        int candidateLimit,
        boolean candidateTruncated,
        String retrievalMode,
        String fallbackReason,
        List<Long> adoptedMemoryIds,
        List<L2MemoryRecallExclusion> exclusions) {

    public static L2MemoryRecallResult empty(int candidateLimit, String retrievalMode, String fallbackReason) {
        return new L2MemoryRecallResult(
                List.of(), 0L, 0, candidateLimit, false,
                retrievalMode, fallbackReason, List.of(), List.of());
    }
}
