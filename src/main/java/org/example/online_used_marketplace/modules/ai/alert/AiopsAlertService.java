package org.example.online_used_marketplace.modules.ai.alert;

public interface AiopsAlertService {

    AiopsAlertWebhookVO receive(AlertmanagerWebhookDTO webhook, String presentedToken);

    AiopsAlertDeliveryVO getDelivery(Long deliveryId);

    AiopsChangeEventVO createChangeEvent(AiopsChangeEventCreateDTO request);
}
