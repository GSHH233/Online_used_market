package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.vo.KnowledgeRerankResult;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;

import java.util.List;

public interface KnowledgeReranker {

    KnowledgeRerankResult rerank(String query, List<RagSourceVO> candidates, int requestedTopK);
}
