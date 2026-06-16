package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.WsMessageType;

/**
 * WebSocket 聊天取消请求
 */
@Data
public class WsChatCancelRequestDTO {

    private WsMessageType type = WsMessageType.CHAT_CANCEL;

    @NotBlank(message = "requestId不能为空")
    @Size(max = 64, message = "requestId长度不能超过64")
    private String requestId;

    @Size(max = 200, message = "取消原因长度不能超过200")
    private String reason;
}
