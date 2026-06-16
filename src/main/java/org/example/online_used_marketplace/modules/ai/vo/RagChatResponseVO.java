package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.List;

/**
 * RAG问答响应
 */
@Data
public class RagChatResponseVO {
    private String answer;
    private List<RagSourceVO> sources;
    private Integer retrievalMs;
    private Integer promptTokens;
    private Integer completionTokens;
}
