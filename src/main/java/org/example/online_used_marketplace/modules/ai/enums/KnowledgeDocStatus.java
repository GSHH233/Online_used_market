package org.example.online_used_marketplace.modules.ai.enums;

/**
 * 知识文档状态
 */
public enum KnowledgeDocStatus {
    DRAFT,
    ACTIVE,
    /** Superseded version kept for a bounded rollback window and excluded from retrieval. */
    RETIRED,
    DISABLED
}
