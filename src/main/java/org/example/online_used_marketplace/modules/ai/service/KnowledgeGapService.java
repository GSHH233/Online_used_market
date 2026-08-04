package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.KnowledgeGapLinkDocDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeGapUpdateDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeGapUnresolvedFeedbackDTO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeGapMetricsVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeGapRegressionVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeGapVO;

import java.time.LocalDateTime;
import java.util.List;

public interface KnowledgeGapService {
    KnowledgeGapVO captureUserUnresolved(KnowledgeGapUnresolvedFeedbackDTO request);
    List<KnowledgeGapVO> list(String status, String priority, Integer limit);
    KnowledgeGapVO get(Long gapId);
    KnowledgeGapVO update(Long gapId, KnowledgeGapUpdateDTO request);
    KnowledgeGapVO linkDoc(Long gapId, KnowledgeGapLinkDocDTO request);
    KnowledgeGapRegressionVO regress(Long gapId);
    List<KnowledgeGapRegressionVO> listRegressions(Long gapId, Integer limit);
    KnowledgeGapMetricsVO metrics(LocalDateTime from, LocalDateTime to);
}
