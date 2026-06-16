package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.List;

/**
 * 知识检索返回对象
 */
@Data
public class KnowledgeSearchResponseVO {

    private String query;

    private Integer topK;

    private Integer total;

    private List<KnowledgeSearchHitVO> hits;

    /**
     * 检索模式：vector / hybrid
     */
    private String retrievalMode;

    /**
     * 调试信息（MVP）：记录各召回通道命中数与融合参数
     */
    private String debugInfo;

    private Integer vectorHitCount;

    private Integer keywordHitCount;
}
