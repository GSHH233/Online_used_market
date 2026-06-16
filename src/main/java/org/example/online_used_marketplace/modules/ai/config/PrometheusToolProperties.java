package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.tools.prometheus")
public class PrometheusToolProperties {

    private boolean enabled = true;

    private String baseUrl = "http://localhost:9090";

    private int timeoutMs = 3000;

    private int defaultLimit = 20;

    private int maxLimit = 100;

    private int defaultRangeMinutes = 30;

    private String defaultStep = "60s";
}
