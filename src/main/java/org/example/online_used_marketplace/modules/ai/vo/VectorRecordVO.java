package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * 向量存储记录
 */
@Data
public class VectorRecordVO {

    private Long docId;

    private Long chunkId;

    private String content;

    private float[] vector;

    /**
     * 扩展元信息（JSON字符串）
     */
    private KnowledgeMetadata metadata;
}
