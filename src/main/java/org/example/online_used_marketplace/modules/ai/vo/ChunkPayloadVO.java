package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * 文档切片载荷
 */
@Data
public class ChunkPayloadVO {

    private Integer chunkIndex;

    private String content;

    /**
     * 可先按字符估算，后续替换为token计数
     */
    private Integer tokenCount;

    /**
     * 扩展元信息（JSON字符串）
     */
    private String metadata;
}
