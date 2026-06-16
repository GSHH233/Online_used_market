package org.example.online_used_marketplace.modules.ai.messaging;

import com.rabbitmq.client.Channel;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionRabbitProperties;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexTaskConsumerTest {

    private KnowledgeIndexService knowledgeIndexService;
    private KnowledgeIndexTaskPublisher publisher;
    private Channel channel;
    private KnowledgeIndexTaskConsumer consumer;
    private Message amqpMessage;

    @BeforeEach
    void setUp() {
        knowledgeIndexService = mock(KnowledgeIndexService.class);
        publisher = mock(KnowledgeIndexTaskPublisher.class);
        channel = mock(Channel.class);
        KnowledgeIngestionRabbitProperties properties = new KnowledgeIngestionRabbitProperties();
        properties.setMaxRetries(2);
        consumer = new KnowledgeIndexTaskConsumer(knowledgeIndexService, publisher, properties);
        amqpMessage = MessageBuilder.withBody(new byte[0]).setDeliveryTag(77L).build();
    }

    @Test
    void shouldAckSuccessfulTask() throws Exception {
        KnowledgeIndexTaskMessage payload = new KnowledgeIndexTaskMessage(10L, 0, LocalDateTime.now());

        consumer.consume(payload, amqpMessage, channel);

        verify(knowledgeIndexService).executeQueuedTask(10L, 0);
        verify(channel).basicAck(77L, false);
        verify(publisher, never()).publishRetry(any());
    }

    @Test
    void shouldQueueRetryAndAckOriginalDelivery() throws Exception {
        KnowledgeIndexTaskMessage payload = new KnowledgeIndexTaskMessage(11L, 0, LocalDateTime.now());
        doThrow(new IllegalStateException("temporary failure"))
                .when(knowledgeIndexService).executeQueuedTask(11L, 0);
        when(knowledgeIndexService.markTaskQueued(11L, 1)).thenReturn(true);
        when(publisher.publishRetry(any())).thenReturn(true);

        consumer.consume(payload, amqpMessage, channel);

        verify(knowledgeIndexService).markTaskQueued(11L, 1);
        verify(publisher).publishRetry(any());
        verify(channel).basicAck(77L, false);
    }

    @Test
    void shouldDeadLetterAfterRetriesExhausted() throws Exception {
        KnowledgeIndexTaskMessage payload = new KnowledgeIndexTaskMessage(12L, 2, LocalDateTime.now());
        doThrow(new IllegalStateException("permanent failure"))
                .when(knowledgeIndexService).executeQueuedTask(12L, 2);

        consumer.consume(payload, amqpMessage, channel);

        verify(knowledgeIndexService).markTaskDeadLettered(12L, 2);
        verify(channel).basicReject(77L, false);
        verify(publisher, never()).publishRetry(any());
    }
}
