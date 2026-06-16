package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * WebSocket 聊天ACK
 */
@Data
public class WsChatAckVO {

    private String requestId;

    private String traceId;

    private String message;
}
