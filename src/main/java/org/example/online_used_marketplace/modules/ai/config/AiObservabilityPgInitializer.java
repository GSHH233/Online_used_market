package org.example.online_used_marketplace.modules.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiObservabilityPgInitializer {

    @Qualifier("memoryGraphJdbcTemplate")
    private final JdbcTemplate jdbcTemplate;
    private final MemoryGraphPgProperties properties;

    @PostConstruct
    public void initSchema() {
        if (!Boolean.TRUE.equals(properties.getEnabled()) || !Boolean.TRUE.equals(properties.getAutoInit())) {
            return;
        }
        try (InputStream in = new ClassPathResource("sql/ai_observability_pg16_init.sql").getInputStream()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            jdbcTemplate.execute(sql);
            log.info("[AiObservability] PG schema initialized.");
        } catch (Exception ex) {
            log.warn("[AiObservability] PG schema init skipped/failed: {}", ex.getMessage());
        }
    }
}
