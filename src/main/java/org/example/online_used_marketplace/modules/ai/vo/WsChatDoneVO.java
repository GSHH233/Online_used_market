package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.List;

/**
 * WebSocket 聊天完成消息
 */
@Data
public class WsChatDoneVO {

    private String requestId;

    private String traceId;

    private String answer;

    private List<RagSourceVO> sources;

    private Boolean lowConfidence;

    private Boolean cancelled;

    /**
     * 置信度分级：HIGH / MEDIUM / LOW
     */
    private String confidenceLevel;

    /**
     * 混合检索与置信度判定调试信息
     */
    private String confidenceDebug;

    /**
     * 本次回答使用的检索模式：vector / hybrid
     */
    private String retrievalMode;

    /**
     * 检索调试信息（双通道命中、融合参数等）
     */
    private String retrievalDebug;

    /**
     * 偏好参数自动填充调试信息
     */
    private String preferenceAutofillDebug;

    /**
     * RAG Chat 最终组装出的完整 prompt，仅供 eval/debug 使用。
     */
    private String promptDebug;

    /**
     * 记忆上下文组装调试信息，JSON 格式，仅供 eval/debug 使用。
     */
    private String memoryAssemblyDebug;

    /**
     * LLM / EVIDENCE_FALLBACK / EVIDENCE_REFUSAL / NO_EVIDENCE
     */
    private String generationMode;

    private Boolean fallbackUsed = false;

    private String fallbackReason;

    private Integer tokenInputBudget;

    private Integer tokenOutputBudget;

    private Integer originalPromptTokens;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer trimmedTokens;

    private Boolean tokenUsageEstimated;

    private Long contextAssemblyElapsedMs;

    private Long retrievalElapsedMs;

    private Long generationElapsedMs;

    private Long totalElapsedMs;

    private Boolean evaluationMode = false;
}
