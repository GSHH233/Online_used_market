package org.example.online_used_marketplace.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(
        basePackages = {
                "org.example.online_used_marketplace.modules.ai.mapper",
                "org.example.online_used_marketplace.modules.ai.mapper.l2",
                "org.example.online_used_marketplace.modules.favorite.mapper",
                "org.example.online_used_marketplace.modules.order.mapper",
                "org.example.online_used_marketplace.modules.product.mapper",
                "org.example.online_used_marketplace.modules.user.mapper"
        },
        sqlSessionTemplateRef = "mysqlSqlSessionTemplate"
)
public class MySqlMapperScanConfig {
}
