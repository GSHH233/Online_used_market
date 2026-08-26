package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.aiops.alertmanager")
public class AiopsAlertProperties {

    private boolean enabled = false;

    private String webhookToken = "";

    private boolean rabbitEnabled = true;

    private int maxAlertsPerDelivery = 100;

    private int maxStringLength = 4096;

    private int maxMapEntries = 32;

    private long confirmTimeoutMs = 3000L;

    private int maxRetries = 2;

    private long retryDelayMs = 3000L;

    private int consumerConcurrency = 1;

    private int prefetch = 1;

    private String exchange = "ai.aiops.alert.investigation.exchange";

    private String queue = "ai.aiops.alert.investigation.queue";

    private String routingKey = "ai.aiops.alert.investigation";
}
