package org.example.online_used_marketplace.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.memory-graph.pgsql")
public class MemoryGraphPgProperties {

    private Boolean enabled = true;

    private Boolean autoInit = true;

    private String jdbcUrl = "jdbc:postgresql://localhost:5432/used_market";

    private String username = "postgres";

    private String password = "";

    private String driverClassName = "org.postgresql.Driver";
}
