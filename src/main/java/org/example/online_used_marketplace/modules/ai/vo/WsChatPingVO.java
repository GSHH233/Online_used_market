package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * WebSocket PING 消息体
 */
@Data
public class WsChatPingVO {
    private String requestId;
    private String traceId;
    private Long ts;
}
