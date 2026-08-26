package org.example.online_used_marketplace.modules.ai.enums;

/**
 * RAG 入库任务阶段
 */
public enum KnowledgeIndexTaskStage {
    CREATED,
    CONSISTENCY_CHECK,
    STAGING,
    CLEANUP,
    SPLIT,
    DB_WRITE,
    EMBEDDING,
    VECTOR_WRITE,
    ES_WRITE,
    VERIFY,
    FINISHED
}
