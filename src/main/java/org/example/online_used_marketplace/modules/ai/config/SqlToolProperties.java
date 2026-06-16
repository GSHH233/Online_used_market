package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 工具配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.tools.sql")
public class SqlToolProperties {

    /**
     * 默认返回条数
     */
    private Integer defaultLimit = 100;

    /**
     * 最大返回条数
     */
    private Integer maxLimit = 500;

    /**
     * SQL 执行超时（毫秒）
     */
    private Integer timeoutMs = 3000;

    /**
     * 可访问数据表白名单
     */
    private List<String> tableWhitelist = new ArrayList<>();
}
