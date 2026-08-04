package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.knowledge")
public class KnowledgeIngestionProperties {

    private final Ingestion ingestion = new Ingestion();
    private final Chunking chunking = new Chunking();
    private final QueryRewrite queryRewrite = new QueryRewrite();

    @Data
    public static class Ingestion {
        private int maxFileSizeMb = 20;
        private int maxExtractedChars = 2_000_000;
        private int maxPdfPages = 300;
        private long parserTimeoutMs = 30_000;
        private String rawBucket = "knowledge-source";
        private String parserVersion = "spring-ai-reader-1.0.3-v1";
        private String embeddingModel = "text-embedding-v3";
    }

    @Data
    public static class Chunking {
        private String strategyVersion = "structure-semantic-v1";
        private int targetTokens = 450;
        private int maxTokens = 600;
        private int minTokens = 100;
        private int overlapTokens = 80;
        private boolean semanticEnabled = true;
        private String semanticModelId = "configured-chat-model";
        private long semanticTimeoutMs = 8_000;
        private int semanticWindowTokens = 2_500;
        private long semanticCacheTtlMinutes = 1_440;
        private int semanticCacheMaxEntries = 2_000;
    }

    @Data
    public static class QueryRewrite {
        private boolean enabled = true;
        private boolean shadowMode = true;
        private String modelId = "configured-chat-model";
        private long cacheTtlSeconds = 300;
        private int cacheMaxEntries = 1_000;
        private long timeoutMs = 2_500;
        private int maxSubQueries = 3;
        private int maxSynonymQueries = 2;
        private double lowConfidenceThreshold = 0.35;
        private int totalCandidateBudget = 60;
    }
}
