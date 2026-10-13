package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphEdgeVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphExtractResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphNodeVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphCleanupVO;

import java.util.List;

public interface MemoryGraphService {

    MemoryGraphExtractResponseVO extractAndSave(String userId, String sessionId, String content, String source);

    List<MemoryGraphNodeVO> listNodes(String userId, String topic, Integer limit);

    List<MemoryGraphEdgeVO> listEdges(String userId, String fromLabel, String toLabel, Integer limit);

    MemoryGraphCleanupVO deleteUserGraph(String userId);
}
