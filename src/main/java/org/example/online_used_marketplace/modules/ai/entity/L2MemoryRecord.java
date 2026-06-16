package org.example.online_used_marketplace.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_l2_memory_record")
public class L2MemoryRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private String sessionId;

    /** FACT / PREFERENCE */
    private String memoryType;

    private String content;

    /** [0,1] */
    private Double importance;

    /** 预留embedding序列化（L2 v1先不用pgvector） */
    private String embeddingJson;

    @TableField("metadata_json")
    private String metadataJson;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
