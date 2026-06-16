package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * WebSocket 流式增量片段
 */
@Data
public class WsChatDeltaVO {

    private String requestId;

    private String traceId;

    /**
     * 本次增量文本
     */
    private String delta;

    /**
     * 是否最后一个增量片段
     */
    private Boolean done;
}
