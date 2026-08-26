package org.example.online_used_marketplace.modules.ai.alert;

import com.rabbitmq.client.Channel;
import org.example.online_used_marketplace.modules.ai.config.AiopsAlertProperties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiopsAlertInvestigationListenerTest {

    @Test
    void missingHandlerIsRetriedAndEventuallyRejectedToDlq() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiopsAlertRabbitPublisher publisher = mock(AiopsAlertRabbitPublisher.class);
        AiopsAlertProperties properties = new AiopsAlertProperties();
        properties.setMaxRetries(0);
        ObjectProvider<AiopsAlertInvestigationHandler> handlerProvider = mock(ObjectProvider.class);
        when(handlerProvider.getIfAvailable()).thenReturn(null);
        Channel channel = mock(Channel.class);
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(12L);
        Message rawMessage = new Message(new byte[0], messageProperties);

        AiopsAlertInvestigationListener listener = new AiopsAlertInvestigationListener(
                jdbcTemplate, publisher, properties, handlerProvider);
        AiopsAlertInvestigationMessage message = new AiopsAlertInvestigationMessage(
                1L, 2L, "fp", "group", "trace", "payload", null);

        listener.onMessage(message, rawMessage, channel);

        verify(channel).basicReject(12L, false);
        verify(jdbcTemplate).update(anyString(), eq("FAILED"), anyString(), eq(1L));
    }

    @Test
    void missingHandlerDoesNotAckWhenRetryIsPublished() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiopsAlertRabbitPublisher publisher = mock(AiopsAlertRabbitPublisher.class);
        when(publisher.publishRetry(any(), eq(1))).thenReturn(true);
        AiopsAlertProperties properties = new AiopsAlertProperties();
        properties.setMaxRetries(1);
        ObjectProvider<AiopsAlertInvestigationHandler> handlerProvider = mock(ObjectProvider.class);
        when(handlerProvider.getIfAvailable()).thenReturn(null);
        Channel channel = mock(Channel.class);
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(13L);
        Message rawMessage = new Message(new byte[0], messageProperties);

        AiopsAlertInvestigationListener listener = new AiopsAlertInvestigationListener(
                jdbcTemplate, publisher, properties, handlerProvider);
        listener.onMessage(new AiopsAlertInvestigationMessage(1L, 2L, "fp", "group", "trace", "payload", null),
                rawMessage, channel);

        verify(publisher).publishRetry(any(), eq(1));
        verify(channel).basicAck(13L, false);
    }
}
