package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.embedding")
public class EmbeddingBatchProperties {

    private int maxBatchSize = 10;
}
