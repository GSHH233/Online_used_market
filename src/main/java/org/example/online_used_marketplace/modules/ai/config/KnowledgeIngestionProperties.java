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
    private final Lifecycle lifecycle = new Lifecycle();
    private final Retrieval retrieval = new Retrieval();

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

    @Data
    public static class Lifecycle {
        /** Keep pre-lifecycle DRAFT documents readable until they are reindexed. */
        private boolean allowLegacyDrafts = true;
        /** Retired revisions remain recoverable and physically indexed for this window. */
        private int retiredRetentionDays = 7;
        private boolean cleanupEnabled = true;
        private long cleanupInitialDelayMs = 60_000;
        private long cleanupFixedDelayMs = 3_600_000;
        private int cleanupBatchSize = 20;
    }

    @Data
    public static class Retrieval {
        private boolean rerankEnabled = true;
        private boolean dynamicTopKEnabled = true;
        private int candidateK = 20;
        private int maxCandidateK = 40;
        private int evidenceCandidatesPerRequirement = 2;
        private int minResults = 1;
        private int maxPerLogicalKnowledge = 2;
        private double minRerankScore = 0.18;
        private double relativeScoreThreshold = 0.78;
        private double retrievalWeight = 0.30;
        private double contentWeight = 0.40;
        private double titleWeight = 0.25;
        private double trustWeight = 0.05;
        private double conceptWeight = 0.35;
        private double diversityLambda = 0.85;
        private boolean facetCoverageEnabled = true;
        private double facetCoverageWeight = 0.25;
        private double minFacetCoverage = 0.80;
        private double facetMismatchPenalty = 0.70;
        private boolean evidencePlanEnabled = true;
        private double evidencePlanWeight = 0.40;
        private double minEvidenceCoverage = 0.90;
        private int parallelism = 6;
        private int parallelQueueCapacity = 100;
        private long parallelTimeoutMs = 5_000;
    }
}
