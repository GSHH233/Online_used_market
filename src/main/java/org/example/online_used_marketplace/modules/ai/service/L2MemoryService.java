package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.memory.L2MemoryDeleteCommand;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryMutationResult;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallQuery;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallResult;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryWriteCommand;
import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;
import org.example.online_used_marketplace.modules.ai.vo.L2SessionSummarySnapshot;
import org.example.online_used_marketplace.modules.ai.vo.L2SessionSummaryWriteResult;

import java.util.List;

public interface L2MemoryService {

    void saveMemory(String userId, String sessionId, String memoryType, String content, Double importance);

    void saveMemory(String userId, String sessionId, String memoryType, String content, Double importance, String metadataJson);

    L2MemoryMutationResult upsertSlotMemory(L2MemoryWriteCommand command);

    L2MemoryMutationResult deleteSlotMemory(L2MemoryDeleteCommand command);

    List<L2MemoryHitVO> listActiveSlotMemories(String userId, String memoryType);

    List<L2MemoryHitVO> listActiveMemoriesByType(String userId, String memoryType, int limit);

    L2MemoryHitVO latestSessionSummary(String userId, String sessionId);

    L2SessionSummarySnapshot latestSessionSummarySnapshot(String userId, String sessionId);

    L2SessionSummaryWriteResult saveSessionSummaryCas(
            String userId,
            String sessionId,
            String content,
            Double importance,
            String metadataJson,
            long expectedRevision);

    List<L2MemoryHitVO> retrieve(String userId, String sessionId, String query, int topK, double threshold);

    L2MemoryRecallResult recall(L2MemoryRecallQuery query);
}
