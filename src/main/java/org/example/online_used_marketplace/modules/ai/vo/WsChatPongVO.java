package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * WebSocket PONG 消息体
 */
@Data
public class WsChatPongVO {
    private String requestId;
    private String traceId;
    private Long ts;
    private String message;
}
