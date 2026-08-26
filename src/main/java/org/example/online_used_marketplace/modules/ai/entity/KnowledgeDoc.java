package org.example.online_used_marketplace.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 知识文档
 */
@Data
@Transactional
@TableName("ai_knowledge_doc")
public class KnowledgeDoc {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /**
     * 来源类型：RULE/FAQ/GUIDE/MARKET
     */
    private String sourceType;

    private String sourceUri;

    private String fileName;

    private String mimeType;

    private String fileHash;

    private String parserType;

    private String parserVersion;

    private String structureJson;

    private String chunkStrategyVersion;

    private String embeddingModel;

    private String activeIndexVersion;

    private String logicalKnowledgeId;

    private String contentHash;

    private Integer revision;

    private Long supersedesDocId;

    private Boolean lifecycleManaged;

    private LocalDateTime publishedAt;

    /** Time at which an ACTIVE revision was superseded. */
    private LocalDateTime retiredAt;

    /** Earliest time at which the retired revision's index artifacts may be deleted. */
    private LocalDateTime retentionUntil;

    /** PENDING/RUNNING/COMPLETED/FAILED for delayed index artifact cleanup. */
    private String cleanupStatus;

    private String cleanupError;

    private LocalDateTime cleanedAt;

    /**
     * 文档原文
     */
    private String content;

    /**
     * 标签，逗号分隔
     */
    private String tags;

    /**
     * 来源可信等级：HIGH/MEDIUM/LOW/UNKNOWN
     */
    private String sourceTrustLevel;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    private String docVersion;

    private String disabledReason;

    /**
     * 状态：DRAFT/ACTIVE/RETIRED/DISABLED
     */
    private String status;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    @TableLogic
    private Integer isDeleted;
}
