package org.example.online_used_marketplace.modules.ai.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.service.impl.PublishAssistProductLinkWriter;
import org.example.online_used_marketplace.modules.product.event.ProductPublishedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductPublishedListener {

    private final PublishAssistProductLinkWriter writer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProductPublished(ProductPublishedEvent event) {
        if (!StringUtils.hasText(event.assistId())) {
            return;
        }
        try {
            // Cross-bean invocation ensures the post-commit write gets a fresh transaction.
            writer.linkProduct(event);
        } catch (RuntimeException ex) {
            String assistId = event.assistId().replaceAll("[\\p{Cntrl}]", "_");
            assistId = assistId.substring(0, Math.min(assistId.length(), 64));
            log.warn("Product assist association failed: productId={}, assistId={}, failureType={}",
                    event.productId(), assistId, ex.getClass().getSimpleName());
        }
    }
}
