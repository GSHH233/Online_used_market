package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.service.PublishAssistFeedbackService;
import org.example.online_used_marketplace.modules.product.event.ProductPublishedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PublishAssistProductLinkWriter {

    private final PublishAssistFeedbackService feedback;

    @Transactional(transactionManager = "mysqlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void linkProduct(ProductPublishedEvent event) {
        feedback.linkProduct(event.assistId(), event.sellerId(), event.productId(), event.toProductCreateDTO());
    }
}
