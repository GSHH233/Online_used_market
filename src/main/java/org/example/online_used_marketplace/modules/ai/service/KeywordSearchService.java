package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;

import java.util.List;

/**
 * 关键词检索服务（优先 ES/BM25，可在实现中做降级）。
 */
public interface KeywordSearchService {

    List<RagSourceVO> searchByKeyword(String question, int topK, KnowledgeSearchFilterDTO filter);

    /**
     * 返回本次检索调试信息，如 esUsed / fallback 等。
     */
    String getLastDebugInfo();
}
