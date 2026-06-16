package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.chat.memory")
public class ChatMemoryProperties {

    /** 是否启用短期记忆 */
    private boolean enabled = true;

    /** 最近保留轮数（Q/A 各一条） */
    private int maxTurns = 5;

    /** 过期时间（分钟） */
    private long ttlMinutes = 30;
}
