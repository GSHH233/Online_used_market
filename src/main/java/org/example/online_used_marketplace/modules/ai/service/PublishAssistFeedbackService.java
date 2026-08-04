package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ProductRiskOutcomeRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.PublishAssistRecordQueryDTO;
import org.example.online_used_marketplace.modules.ai.dto.PublishAssistFeedbackRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductRiskOutcomeVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistMetricsVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistRecordVO;
import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;

import java.time.LocalDateTime;
import java.util.List;

public interface PublishAssistFeedbackService {

    void recordAssist(String assistId, String traceId, Long userId,
                      ProductPublishAssistRequestDTO request, ProductPublishAssistVO result);

    PublishAssistRecordVO feedback(String assistId, Long userId, PublishAssistFeedbackRequestDTO request);

    PublishAssistRecordVO getOwned(String assistId, Long userId);

    PublishAssistRecordVO getInternal(String assistId);

    List<PublishAssistRecordVO> listRecords(PublishAssistRecordQueryDTO query);

    void linkProduct(String assistId, Long userId, Long productId, ProductCreateDTO product);

    ProductRiskOutcomeVO recordOutcome(Long productId, Long operatorId, ProductRiskOutcomeRequestDTO request);

    PublishAssistMetricsVO metrics(LocalDateTime from, LocalDateTime to);
}
