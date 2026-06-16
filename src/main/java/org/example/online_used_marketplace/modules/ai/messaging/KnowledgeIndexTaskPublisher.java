package org.example.online_used_marketplace.modules.ai.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionRabbitConfig;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionRabbitProperties;
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
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.knowledge.ingestion.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexTaskPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final KnowledgeIngestionRabbitProperties properties;

    public boolean publishInitial(KnowledgeIndexTaskMessage message) {
        return publish(KnowledgeIngestionRabbitConfig.EXCHANGE,
                KnowledgeIngestionRabbitConfig.ROUTING_KEY,
                message);
    }

    public boolean publishRetry(KnowledgeIndexTaskMessage message) {
        return publish(KnowledgeIngestionRabbitConfig.RETRY_EXCHANGE,
                KnowledgeIngestionRabbitConfig.RETRY_ROUTING_KEY,
                message);
    }

    private boolean publish(String exchange, String routingKey, KnowledgeIndexTaskMessage payload) {
        CorrelationData correlationData = new CorrelationData(
                "knowledge-index-" + payload.getTaskId() + "-" + payload.getRetryCount() + "-" + UUID.randomUUID());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setHeader("x-task-id", payload.getTaskId());
                message.getMessageProperties().setHeader("x-retry-count", payload.getRetryCount());
                return message;
            }, correlationData);

            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(Math.max(100L, properties.getConfirmTimeoutMs()), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                log.warn("[KnowledgeIndexMQ] publish nack, taskId={}, retryCount={}, reason={}",
                        payload.getTaskId(), payload.getRetryCount(), confirm.getReason());
                return false;
            }
            if (correlationData.getReturned() != null) {
                log.warn("[KnowledgeIndexMQ] message returned, taskId={}, retryCount={}, reply={}",
                        payload.getTaskId(), payload.getRetryCount(), correlationData.getReturned().getReplyText());
                return false;
            }
            log.info("[KnowledgeIndexMQ] published, taskId={}, retryCount={}, exchange={}, routingKey={}",
                    payload.getTaskId(), payload.getRetryCount(), exchange, routingKey);
            return true;
        } catch (AmqpException e) {
            log.warn("[KnowledgeIndexMQ] publish failed, taskId={}, retryCount={}, reason={}",
                    payload.getTaskId(), payload.getRetryCount(), e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("[KnowledgeIndexMQ] publish confirm failed, taskId={}, retryCount={}, reason={}",
                    payload.getTaskId(), payload.getRetryCount(), e.getMessage());
            return false;
        }
    }
}
