package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * WebSocket 聊天错误消息
 */
@Data
public class WsChatErrorVO {

    private String requestId;

    private String traceId;

    private Integer code;

    private String message;
}
