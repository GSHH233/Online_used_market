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
     * 状态：DRAFT/ACTIVE/DISABLED
     */
    private String status;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    @TableLogic
    private Integer isDeleted;
}
