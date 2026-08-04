package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchFilterDTO;
import org.example.online_used_marketplace.modules.ai.enums.RetrievalMode;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;

/**
 * 知识检索服务
 */
public interface KnowledgeSearchService {

    KnowledgeSearchResponseVO search(String question, Integer topK, KnowledgeSearchFilterDTO filter);

    KnowledgeSearchResponseVO search(String question, Integer topK, KnowledgeSearchFilterDTO filter, RetrievalMode mode);

    /**
     * 混合检索：向量召回 + 关键词召回 + RRF 融合
     */
    KnowledgeSearchResponseVO searchHybrid(String question, Integer topK, KnowledgeSearchFilterDTO filter);

    KnowledgeSearchResponseVO searchHybrid(String question,
                                           Integer topK,
                                           KnowledgeSearchFilterDTO filter,
                                           boolean rewriteEnabled);
}
