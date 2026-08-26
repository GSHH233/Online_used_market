package org.example.online_used_marketplace.modules.ai.alert;

public interface AiopsAlertDeliveryStateStore {

    void markPublished(Long deliveryId);

    void markResolved(Long deliveryId);

    void markFailed(Long deliveryId, String error);

    void markInvestigationPending(Long deliveryId, String error);

    void markRetryScheduled(Long deliveryId, String error);

    void markInvestigationAccepted(Long deliveryId);
}
