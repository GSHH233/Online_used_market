package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.vo.ChunkPayloadVO;
import org.example.online_used_marketplace.modules.ai.vo.ChunkQualityReportVO;

import java.util.List;

public interface ChunkQualityValidator {
    ChunkQualityReportVO validate(KnowledgeDoc doc,
                                  List<ChunkPayloadVO> chunks,
                                  int minTokens,
                                  int maxTokens);
}
