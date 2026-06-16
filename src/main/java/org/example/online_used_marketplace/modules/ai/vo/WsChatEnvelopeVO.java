package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.WsMessageType;

/**
 * WebSocket 消息统一封装
 */
@Data
public class WsChatEnvelopeVO<T> {

    private WsMessageType type;

    private String requestId;

    private Long ts;

    private T payload;

    public static <T> WsChatEnvelopeVO<T> of(WsMessageType type, String requestId, T payload) {
        WsChatEnvelopeVO<T> vo = new WsChatEnvelopeVO<>();
        vo.setType(type);
        vo.setRequestId(requestId);
        vo.setPayload(payload);
        vo.setTs(System.currentTimeMillis());
        return vo;
    }
}
