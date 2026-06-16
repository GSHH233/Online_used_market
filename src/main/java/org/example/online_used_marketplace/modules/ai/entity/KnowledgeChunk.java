package org.example.online_used_marketplace.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeMetadata;

import java.time.LocalDateTime;

/**
 * 知识切片
 */
@Data
@TableName(value = "ai_knowledge_chunk", autoResultMap = true)
public class KnowledgeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    private Integer chunkIndex;

    private String content;

    private Integer tokenCount;

    private String embeddingModel;

    /**
     * 扩展元信息(JSON字符串)
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private KnowledgeMetadata metadata;

    /**
     * 向量存储中的外部ID（如Milvus主键）
     */
    private String vectorRefId;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    @TableLogic
    private Integer isDeleted;
}
