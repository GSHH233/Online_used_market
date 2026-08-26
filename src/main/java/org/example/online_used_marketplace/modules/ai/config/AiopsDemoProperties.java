package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the intentionally small, local-only AIOps demo service.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.aiops.demo")
public class AiopsDemoProperties {

    private boolean enabled = false;

    private List<String> allowedServices = new ArrayList<>(List.of("marketplace-demo-worker"));

    private String redisKeyPrefix = "ai:aiops:demo:service:";
}
