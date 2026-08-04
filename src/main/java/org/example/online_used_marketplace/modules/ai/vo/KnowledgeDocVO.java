package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;

import java.time.LocalDateTime;

/**
 * 知识文档返回对象
 */
@Data
public class KnowledgeDocVO {

    private Long id;

    private String title;

    private KnowledgeSourceType sourceType;

    private String sourceUri;

    private String tags;

    private String sourceTrustLevel;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    private String docVersion;

    private String disabledReason;

    private KnowledgeDocStatus status;

    private LocalDateTime createdTime;

    private String fileName;

    private String mimeType;

    private String fileHash;

    private String parserType;

    private String parserVersion;

    private String chunkStrategyVersion;

    private String embeddingModel;

    private String activeIndexVersion;
}
