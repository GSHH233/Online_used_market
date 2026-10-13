package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.memory.MemoryScope;
import org.example.online_used_marketplace.modules.ai.vo.WsChatDoneVO;

/**
 * @author Admin
 * @ClassName: RagChatService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-05-05  22:24
 * @Version:
 */
public interface RagChatService {
    WsChatDoneVO chat(MemoryScope scope, String question, String traceId, Integer topK, KnowledgeSearchFilterDTO filter);

    /**
     * Isolated evaluation path: executes retrieval and answer generation without
     * writing conversation memory, knowledge-gap audit, or preference records.
     */
    default WsChatDoneVO chatForEvaluation(String sessionId, String question, String traceId,
                                           Integer topK, KnowledgeSearchFilterDTO filter) {
        throw new UnsupportedOperationException("Evaluation path must not write private memory");
    }
}
