package org.example.online_used_marketplace.config;

import com.zaxxer.hikari.HikariDataSource;
import org.example.online_used_marketplace.modules.ai.config.MemoryGraphPgProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

@Configuration
public class MemoryGraphPgConfig {

    @Bean(name = "memoryGraphDataSource")
    public DataSource memoryGraphDataSource(MemoryGraphPgProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getJdbcUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setDriverClassName(properties.getDriverClassName());
        dataSource.setPoolName("memory-graph-pg-pool");
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        return dataSource;
    }

    @Bean(name = "memoryGraphJdbcTemplate")
    public JdbcTemplate memoryGraphJdbcTemplate(@Qualifier("memoryGraphDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "memoryGraphTransactionManager")
    public DataSourceTransactionManager memoryGraphTransactionManager(@Qualifier("memoryGraphDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
