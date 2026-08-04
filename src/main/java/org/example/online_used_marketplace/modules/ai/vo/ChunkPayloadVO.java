package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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

    private List<String> headingPath = new ArrayList<>();

    private Integer pageFrom;

    private Integer pageTo;

    private String contentType;

    private Integer sourceStart;

    private Integer sourceEnd;

    private String semanticTopic;

    private Boolean overlap = false;

    /** Number of leading characters copied from the previous chunk. */
    private Integer overlapChars = 0;
}
