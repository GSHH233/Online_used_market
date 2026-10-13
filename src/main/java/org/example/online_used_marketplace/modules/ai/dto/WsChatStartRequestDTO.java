package org.example.online_used_marketplace.modules.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.WsMessageType;

/**
 * WebSocket 聊天开始请求
 */
@Data
public class WsChatStartRequestDTO {

    private WsMessageType type = WsMessageType.CHAT_START;

    @Size(max = 64, message = "requestId长度不能超过64")
    private String requestId;

    /**
     * Browser WebSocket clients cannot set the Authorization handshake header.
     * The token is accepted only in CHAT_START and is write-only for Jackson.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = 4096, message = "token格式不合法")
    private String token;

    @Pattern(regexp = "[A-Za-z0-9_-]{1,128}", message = "conversationId格式不合法")
    private String conversationId;

    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题长度不能超过500")
    private String question;

    @Min(value = 1, message = "topK最小为1")
    @Max(value = 20, message = "topK最大为20")
    private Integer topK = 5;

    @Valid
    private KnowledgeSearchFilterDTO filter;
}
