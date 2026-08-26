package org.example.online_used_marketplace.modules.ai.alert;

import org.example.online_used_marketplace.modules.ai.config.AiopsAlertProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "ai.aiops.alertmanager", name = "rabbit-enabled", havingValue = "true", matchIfMissing = true)
public class AiopsAlertRabbitConfig {

    public static final String EXCHANGE = "ai.aiops.alert.investigation.exchange";
    public static final String QUEUE = "ai.aiops.alert.investigation.queue";
    public static final String ROUTING_KEY = "ai.aiops.alert.investigation";
    public static final String RETRY_EXCHANGE = "ai.aiops.alert.investigation.retry.exchange";
    public static final String RETRY_QUEUE = "ai.aiops.alert.investigation.retry.queue";
    public static final String RETRY_ROUTING_KEY = "ai.aiops.alert.investigation.retry";
    public static final String DEAD_EXCHANGE = "ai.aiops.alert.investigation.dead.exchange";
    public static final String DEAD_QUEUE = "ai.aiops.alert.investigation.dead.queue";
    public static final String DEAD_ROUTING_KEY = "ai.aiops.alert.investigation.dead";

    @Bean
    public DirectExchange aiopsAlertInvestigationExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange aiopsAlertInvestigationRetryExchange() {
        return new DirectExchange(RETRY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange aiopsAlertInvestigationDeadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue aiopsAlertInvestigationQueue(AiopsAlertProperties properties) {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue aiopsAlertInvestigationRetryQueue(AiopsAlertProperties properties) {
        int retryDelayMs = (int) Math.min(Integer.MAX_VALUE, Math.max(100L, properties.getRetryDelayMs()));
        return QueueBuilder.durable(RETRY_QUEUE)
                .ttl(retryDelayMs)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue aiopsAlertInvestigationDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding aiopsAlertInvestigationBinding(
            @org.springframework.beans.factory.annotation.Qualifier("aiopsAlertInvestigationQueue") Queue queue,
            @org.springframework.beans.factory.annotation.Qualifier("aiopsAlertInvestigationExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY);
    }

    @Bean
    public Binding aiopsAlertInvestigationRetryBinding(
            @org.springframework.beans.factory.annotation.Qualifier("aiopsAlertInvestigationRetryQueue") Queue queue,
            @org.springframework.beans.factory.annotation.Qualifier("aiopsAlertInvestigationRetryExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding aiopsAlertInvestigationDeadBinding(
            @org.springframework.beans.factory.annotation.Qualifier("aiopsAlertInvestigationDeadQueue") Queue queue,
            @org.springframework.beans.factory.annotation.Qualifier("aiopsAlertInvestigationDeadExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(DEAD_ROUTING_KEY);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory aiopsAlertRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @org.springframework.beans.factory.annotation.Qualifier("rabbitJsonMessageConverter") MessageConverter messageConverter,
            AiopsAlertProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        int concurrency = Math.max(1, properties.getConsumerConcurrency());
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        factory.setPrefetchCount(Math.max(1, properties.getPrefetch()));
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
