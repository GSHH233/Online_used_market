package org.example.online_used_marketplace.modules.ai.event;

import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;

import java.util.List;

public record RagChatOutcomeEvent(
        String query,
        String sessionId,
        String traceId,
        Integer topK,
        String retrievalMode,
        String retrievalDebug,
        Boolean lowConfidence,
        String answer,
        List<RagSourceVO> sources,
        String reasonCode
) {
}
