package org.example.online_used_marketplace.modules.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class KnowledgeIngestionMySqlInitializer {

    private final JdbcTemplate jdbcTemplate;

    @Value("${ai.knowledge.ingestion.auto-init:true}")
    private boolean autoInit;

    public KnowledgeIngestionMySqlInitializer(@Qualifier("mysqlDataSource") DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initialize() {
        if (!autoInit) {
            return;
        }
        try {
            if (!tableExists("ai_knowledge_doc")) {
                log.info("[KnowledgeIngestion] ai_knowledge_doc does not exist yet; schema upgrade deferred");
                return;
            }
            for (Map.Entry<String, String> column : columns().entrySet()) {
                addColumnIfMissing("ai_knowledge_doc", column.getKey(), column.getValue());
            }
            for (Map.Entry<String, String> column : chunkColumns().entrySet()) {
                addColumnIfMissing("ai_knowledge_chunk", column.getKey(), column.getValue());
            }
            addIndexIfMissing(
                    "idx_ai_doc_file_hash",
                    "`file_hash`, `parser_version`, `chunk_strategy_version`");
            addIndexIfMissing(
                    "idx_ai_doc_ingestion_version",
                    "`file_hash`, `parser_version`, `chunk_strategy_version`, `embedding_model`");
            addIndexIfMissing(
                    "idx_ai_doc_logical_revision",
                    "`logical_knowledge_id`, `status`, `revision`");
            addIndexIfMissing(
                    "idx_ai_doc_content_hash",
                    "`content_hash`, `source_type`");
            addIndexIfMissing(
                    "idx_ai_doc_retired_cleanup",
                    "`status`, `retention_until`, `cleanup_status`");
            log.info("[KnowledgeIngestion] MySQL schema initialized");
        } catch (Exception ex) {
            throw new IllegalStateException("Knowledge ingestion MySQL schema initialization failed", ex);
        }
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + definition);
            log.info("[KnowledgeIngestion] added column {}.{}", tableName, columnName);
        }
    }

    private void addIndexIfMissing(String indexName, String columns) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'ai_knowledge_doc'
                  AND index_name = ?
                """, Integer.class, indexName);
        if (count == null || count == 0) {
            jdbcTemplate.execute("CREATE INDEX `" + indexName + "` ON `ai_knowledge_doc` (" + columns + ")");
        }
    }

    private Map<String, String> columns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("file_name", "varchar(255) DEFAULT NULL");
        columns.put("mime_type", "varchar(128) DEFAULT NULL");
        columns.put("file_hash", "char(64) DEFAULT NULL");
        columns.put("parser_type", "varchar(64) DEFAULT NULL");
        columns.put("parser_version", "varchar(64) DEFAULT NULL");
        columns.put("structure_json", "longtext DEFAULT NULL");
        columns.put("chunk_strategy_version", "varchar(64) DEFAULT NULL");
        columns.put("embedding_model", "varchar(128) DEFAULT NULL");
        columns.put("active_index_version", "varchar(96) DEFAULT NULL");
        columns.put("logical_knowledge_id", "varchar(128) DEFAULT NULL");
        columns.put("content_hash", "char(64) DEFAULT NULL");
        columns.put("revision", "int NOT NULL DEFAULT 1");
        columns.put("supersedes_doc_id", "bigint DEFAULT NULL");
        columns.put("lifecycle_managed", "tinyint(1) NOT NULL DEFAULT 0");
        columns.put("published_at", "datetime(6) DEFAULT NULL");
        columns.put("retired_at", "datetime(6) DEFAULT NULL");
        columns.put("retention_until", "datetime(6) DEFAULT NULL");
        columns.put("cleanup_status", "varchar(32) DEFAULT NULL");
        columns.put("cleanup_error", "varchar(500) DEFAULT NULL");
        columns.put("cleaned_at", "datetime(6) DEFAULT NULL");
        return columns;
    }

    private Map<String, String> chunkColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("index_version", "varchar(96) DEFAULT NULL");
        columns.put("index_status", "varchar(24) DEFAULT NULL");
        return columns;
    }
}
