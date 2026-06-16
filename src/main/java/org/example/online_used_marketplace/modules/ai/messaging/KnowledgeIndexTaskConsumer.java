package org.example.online_used_marketplace.modules.ai.messaging;

import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionRabbitConfig;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionRabbitProperties;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeIndexService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.knowledge.ingestion.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexTaskConsumer {

    private final KnowledgeIndexService knowledgeIndexService;
    private final KnowledgeIndexTaskPublisher publisher;
    private final KnowledgeIngestionRabbitProperties properties;

    @RabbitListener(
            queues = KnowledgeIngestionRabbitConfig.QUEUE,
            containerFactory = "knowledgeIngestionRabbitListenerContainerFactory"
    )
    public void consume(KnowledgeIndexTaskMessage payload, Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            knowledgeIndexService.executeQueuedTask(payload.getTaskId(), payload.getRetryCount());
            channel.basicAck(deliveryTag, false);
            log.info("[KnowledgeIndexMQ] consumed, taskId={}, retryCount={}",
                    payload.getTaskId(), payload.getRetryCount());
        } catch (Exception executionError) {
            if (payload.getRetryCount() < properties.getMaxRetries()) {
                KnowledgeIndexTaskMessage retry = payload.nextRetry();
                boolean queued = knowledgeIndexService.markTaskQueued(retry.getTaskId(), retry.getRetryCount());
                if (!queued) {
                    channel.basicNack(deliveryTag, false, true);
                    log.warn("[KnowledgeIndexMQ] retry state persistence failed; original delivery requeued, taskId={}, retryCount={}",
                            retry.getTaskId(), retry.getRetryCount());
                    return;
                }
                boolean published = publisher.publishRetry(retry);
                channel.basicAck(deliveryTag, false);
                log.warn("[KnowledgeIndexMQ] execution failed, retry queued={}, published={}, taskId={}, retryCount={}, reason={}",
                        queued, published, retry.getTaskId(), retry.getRetryCount(), executionError.getMessage());
                return;
            }

            knowledgeIndexService.markTaskDeadLettered(payload.getTaskId(), payload.getRetryCount());
            channel.basicReject(deliveryTag, false);
            log.error("[KnowledgeIndexMQ] retries exhausted, moved to DLQ, taskId={}, retryCount={}, reason={}",
                    payload.getTaskId(), payload.getRetryCount(), executionError.getMessage());
        }
    }
}
