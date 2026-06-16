package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.knowledge.ingestion.rabbit")
public class KnowledgeIngestionRabbitProperties {

    private boolean enabled = true;

    private int maxRetries = 2;

    private long retryDelayMs = 3000L;

    private long confirmTimeoutMs = 3000L;

    private int consumerConcurrency = 1;

    private int prefetch = 1;

    private long recoveryInitialDelayMs = 15000L;

    private long recoveryFixedDelayMs = 30000L;

    private long queuedStaleSeconds = 60L;

    private long runningStaleSeconds = 600L;

    private int recoveryBatchSize = 100;
}
