package org.example.online_used_marketplace.modules.ai.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(KnowledgeIngestionRabbitProperties.class)
@ConditionalOnProperty(prefix = "ai.knowledge.ingestion.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIngestionRabbitConfig {

    public static final String EXCHANGE = "ai.knowledge.ingestion.exchange";
    public static final String QUEUE = "ai.knowledge.ingestion.queue";
    public static final String ROUTING_KEY = "ai.knowledge.ingestion.execute";

    public static final String RETRY_EXCHANGE = "ai.knowledge.ingestion.retry.exchange";
    public static final String RETRY_QUEUE = "ai.knowledge.ingestion.retry.queue";
    public static final String RETRY_ROUTING_KEY = "ai.knowledge.ingestion.retry";

    public static final String DEAD_EXCHANGE = "ai.knowledge.ingestion.dead.exchange";
    public static final String DEAD_QUEUE = "ai.knowledge.ingestion.dead.queue";
    public static final String DEAD_ROUTING_KEY = "ai.knowledge.ingestion.dead";

    @Bean
    public DirectExchange knowledgeIngestionExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange knowledgeIngestionRetryExchange() {
        return new DirectExchange(RETRY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange knowledgeIngestionDeadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue knowledgeIngestionQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue knowledgeIngestionRetryQueue(KnowledgeIngestionRabbitProperties properties) {
        int retryDelayMs = (int) Math.min(Integer.MAX_VALUE, Math.max(100L, properties.getRetryDelayMs()));
        return QueueBuilder.durable(RETRY_QUEUE)
                .ttl(retryDelayMs)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue knowledgeIngestionDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding knowledgeIngestionBinding(
            @Qualifier("knowledgeIngestionQueue") Queue knowledgeIngestionQueue,
            @Qualifier("knowledgeIngestionExchange") DirectExchange knowledgeIngestionExchange) {
        return BindingBuilder.bind(knowledgeIngestionQueue)
                .to(knowledgeIngestionExchange)
                .with(ROUTING_KEY);
    }

    @Bean
    public Binding knowledgeIngestionRetryBinding(
            @Qualifier("knowledgeIngestionRetryQueue") Queue knowledgeIngestionRetryQueue,
            @Qualifier("knowledgeIngestionRetryExchange") DirectExchange knowledgeIngestionRetryExchange) {
        return BindingBuilder.bind(knowledgeIngestionRetryQueue)
                .to(knowledgeIngestionRetryExchange)
                .with(RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding knowledgeIngestionDeadBinding(
            @Qualifier("knowledgeIngestionDeadQueue") Queue knowledgeIngestionDeadQueue,
            @Qualifier("knowledgeIngestionDeadExchange") DirectExchange knowledgeIngestionDeadExchange) {
        return BindingBuilder.bind(knowledgeIngestionDeadQueue)
                .to(knowledgeIngestionDeadExchange)
                .with(DEAD_ROUTING_KEY);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory knowledgeIngestionRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Qualifier("rabbitJsonMessageConverter") MessageConverter rabbitJsonMessageConverter,
            KnowledgeIngestionRabbitProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(rabbitJsonMessageConverter);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(Math.max(1, properties.getConsumerConcurrency()));
        factory.setMaxConcurrentConsumers(Math.max(1, properties.getConsumerConcurrency()));
        factory.setPrefetchCount(Math.max(1, properties.getPrefetch()));
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
