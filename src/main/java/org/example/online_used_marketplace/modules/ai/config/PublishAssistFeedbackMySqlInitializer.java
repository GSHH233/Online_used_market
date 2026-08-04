package org.example.online_used_marketplace.modules.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Slf4j
@Component
public class PublishAssistFeedbackMySqlInitializer {

    private final DataSource dataSource;

    @Value("${ai.publish-feedback.auto-init:true}")
    private boolean autoInit;

    public PublishAssistFeedbackMySqlInitializer(@Qualifier("mysqlDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initialize() {
        if (!autoInit) {
            return;
        }
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("sql/publish_assist_feedback_mysql_init.sql"));
            populator.setContinueOnError(false);
            DatabasePopulatorUtils.execute(populator, dataSource);
            log.info("[PublishFeedback] MySQL schema initialized.");
        } catch (Exception ex) {
            log.warn("[PublishFeedback] MySQL schema init skipped/failed: {}", ex.getMessage());
        }
    }
}
