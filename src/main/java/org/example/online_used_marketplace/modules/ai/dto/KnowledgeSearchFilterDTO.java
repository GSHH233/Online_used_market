package org.example.online_used_marketplace.modules.ai.dto;

import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;

import java.util.Map;

/**
 * 知识检索过滤条件
 */
@Data
public class KnowledgeSearchFilterDTO {

    private KnowledgeSourceType sourceType;

    private String category;

    private String riskLevel;

    /**
     * 预留扩展过滤字段
     */
    private Map<String, Object> ext;
}
