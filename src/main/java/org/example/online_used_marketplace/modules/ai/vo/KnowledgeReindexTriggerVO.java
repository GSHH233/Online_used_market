package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStatus;

/**
 * 触发知识重建索引返回对象
 */
@Data
public class KnowledgeReindexTriggerVO {

    private Long taskId;

    private Long docId;

    private KnowledgeIndexTaskStatus status;

    private String message;

    private KnowledgeIndexTaskVO task;
}
