package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;

import java.util.List;

public interface L2MemoryService {

    void saveMemory(String userId, String sessionId, String memoryType, String content, Double importance);

    void saveMemory(String userId, String sessionId, String memoryType, String content, Double importance, String metadataJson);

    List<L2MemoryHitVO> retrieve(String userId, String sessionId, String query, int topK, double threshold);
}
