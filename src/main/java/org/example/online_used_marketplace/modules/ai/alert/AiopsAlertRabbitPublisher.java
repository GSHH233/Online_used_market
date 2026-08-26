package org.example.online_used_marketplace.modules.ai.alert;

import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.AiopsAlertProperties;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.aiops.alertmanager", name = "rabbit-enabled", havingValue = "true", matchIfMissing = true)
public class AiopsAlertRabbitPublisher implements AiopsAlertInvestigationPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AiopsAlertProperties properties;

    public AiopsAlertRabbitPublisher(RabbitTemplate rabbitTemplate, AiopsAlertProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public boolean publish(AiopsAlertInvestigationMessage message) {
        return publishTo(AiopsAlertRabbitConfig.EXCHANGE, AiopsAlertRabbitConfig.ROUTING_KEY, message, 0);
    }

    public boolean publishRetry(AiopsAlertInvestigationMessage message, int attempt) {
        return publishTo(AiopsAlertRabbitConfig.RETRY_EXCHANGE, AiopsAlertRabbitConfig.RETRY_ROUTING_KEY,
                message, attempt);
    }

    private boolean publishTo(String exchange, String routingKey,
                              AiopsAlertInvestigationMessage message, int attempt) {
        CorrelationData correlationData = new CorrelationData(
                "aiops-alert-" + message.getDeliveryId() + "-" + message.getEventId() + "-" + attempt + "-" + UUID.randomUUID());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, message, outbound -> {
                outbound.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                outbound.getMessageProperties().setHeader("x-aiops-delivery-id", message.getDeliveryId());
                outbound.getMessageProperties().setHeader("x-aiops-event-id", message.getEventId());
                outbound.getMessageProperties().setHeader("x-aiops-attempt", attempt);
                return outbound;
            }, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(Math.max(100L, properties.getConfirmTimeoutMs()), TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlationData.getReturned() != null) {
                log.warn("[AiopsAlertMQ] publish rejected, deliveryId={}, eventId={}, ack={}, returned={}",
                        message.getDeliveryId(), message.getEventId(), confirm.isAck(), correlationData.getReturned() != null);
                return false;
            }
            return true;
        } catch (AmqpException ex) {
            log.warn("[AiopsAlertMQ] publish failed, deliveryId={}, eventId={}, reason={}",
                    message.getDeliveryId(), message.getEventId(), ex.getMessage());
            return false;
        } catch (Exception ex) {
            log.warn("[AiopsAlertMQ] publish confirm failed, deliveryId={}, eventId={}, reason={}",
                    message.getDeliveryId(), message.getEventId(), ex.getMessage());
            return false;
        }
    }
}
