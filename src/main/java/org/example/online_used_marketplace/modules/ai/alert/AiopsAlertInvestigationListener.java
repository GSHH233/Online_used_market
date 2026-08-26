package org.example.online_used_marketplace.modules.ai.alert;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.AiopsAlertProperties;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.aiops.alertmanager", name = "rabbit-enabled", havingValue = "true", matchIfMissing = true)
public class AiopsAlertInvestigationListener {

    private final JdbcTemplate jdbcTemplate;
    private final AiopsAlertRabbitPublisher publisher;
    private final AiopsAlertProperties properties;
    private final ObjectProvider<AiopsAlertInvestigationHandler> handlerProvider;

    public AiopsAlertInvestigationListener(
            @Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate,
            AiopsAlertRabbitPublisher publisher,
            AiopsAlertProperties properties,
            ObjectProvider<AiopsAlertInvestigationHandler> handlerProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.publisher = publisher;
        this.properties = properties;
        this.handlerProvider = handlerProvider;
    }

    @RabbitListener(
            queues = AiopsAlertRabbitConfig.QUEUE,
            containerFactory = "aiopsAlertRabbitListenerContainerFactory")
    public void onMessage(AiopsAlertInvestigationMessage message, Message rawMessage, Channel channel)
            throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        if (message == null || message.getDeliveryId() == null || message.getEventId() == null) {
            channel.basicReject(deliveryTag, false);
            return;
        }
        AiopsAlertInvestigationHandler handler = handlerProvider.getIfAvailable();
        if (handler == null) {
            retryOrDeadLetter(message, rawMessage, channel, deliveryTag, "调查处理器未注册");
            return;
        }
        try {
            handler.investigate(message);
            markDelivery("INVESTIGATION_ACCEPTED", "", message.getDeliveryId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            retryOrDeadLetter(message, rawMessage, channel, deliveryTag,
                    "调查处理失败：" + safe(ex.getMessage()));
        }
    }

    private void retryOrDeadLetter(AiopsAlertInvestigationMessage message, Message rawMessage,
                                   Channel channel, long deliveryTag, String reason) throws IOException {
        int attempt = readAttempt(rawMessage);
        if (attempt < Math.max(0, properties.getMaxRetries())
                && publisher.publishRetry(message, attempt + 1)) {
            markDelivery("RETRY_SCHEDULED", reason, message.getDeliveryId());
            channel.basicAck(deliveryTag, false);
            return;
        }
        markDelivery("FAILED", reason + "，已进入 DLQ", message.getDeliveryId());
        channel.basicReject(deliveryTag, false);
    }

    private int readAttempt(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-aiops-attempt");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private void markDelivery(String status, String error, Long deliveryId) {
        jdbcTemplate.update("""
                UPDATE aiops_alert_delivery
                SET processing_status=?, error_msg=?, updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, status, safe(error), deliveryId);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }
}
