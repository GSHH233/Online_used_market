package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

/**
 * @author Admin
 * @ClassName: WsChatCancelVO
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-05-09  21:14
 * @Version:
 */
@Data
public class WsChatCancelVO {
    private String requestId;

    private String traceId;

    private String message;
    private Boolean cancelled;
}
