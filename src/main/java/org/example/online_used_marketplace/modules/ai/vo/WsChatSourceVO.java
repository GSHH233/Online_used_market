package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.List;

/**
 * WebSocket 引用来源消息
 */
@Data
public class WsChatSourceVO {

    private String requestId;

    private String traceId;

    private List<RagSourceVO> sources;
}
