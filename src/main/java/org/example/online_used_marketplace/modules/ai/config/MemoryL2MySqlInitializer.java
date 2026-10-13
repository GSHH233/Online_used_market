package org.example.online_used_marketplace.modules.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class MemoryL2MySqlInitializer {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Value("${ai.memory.l2.auto-init:true}")
    private boolean autoInit;

    public MemoryL2MySqlInitializer(@Qualifier("mysqlDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initialize() {
        if (!autoInit) {
            return;
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("sql/memory_l2_mysql_init.sql"));
        populator.setContinueOnError(false);
        DatabasePopulatorUtils.execute(populator, dataSource);
        for (Map.Entry<String, String> column : recordColumns().entrySet()) {
            addColumnIfMissing("ai_l2_memory_record", column.getKey(), column.getValue());
        }
        addColumnIfMissing("ai_memory_slot", "slot_key",
                "varchar(128) COLLATE utf8mb4_bin NOT NULL DEFAULT ''");
        addIndexIfMissing("ai_l2_memory_record", "uk_ai_l2_owner_idempotency",
                "UNIQUE", "`user_id`, `idempotency_key`");
        addIndexIfMissing("ai_l2_memory_record", "idx_ai_l2_owner_type_status_time",
                "", "`user_id`, `memory_type`, `status`, `updated_time`");
        addIndexIfMissing("ai_l2_memory_record", "idx_ai_l2_owner_session_type_revision",
                "", "`user_id`, `session_id`, `memory_type`, `revision`");
        addIndexIfMissing("ai_l2_memory_record", "idx_ai_l2_validity",
                "", "`status`, `valid_until`");
        addIndexIfMissing("ai_l2_memory_record", "idx_ai_l2_supersedes",
                "", "`supersedes_id`");
        addIndexIfMissing("ai_memory_slot", "uk_ai_memory_slot_scope_v2",
                "UNIQUE", "`owner_user_id`, `scope_type`, `scope_key`, `memory_type`, `slot_key`");
        dropIndexIfExists("ai_memory_slot", "uk_ai_memory_slot_scope");
        log.info("[MemoryL2] MySQL record and slot schema initialized");
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
            log.info("[MemoryL2] added column {}.{}", tableName, columnName);
        }
    }

    private void addIndexIfMissing(String tableName, String indexName, String qualifier, String columns) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, Integer.class, tableName, indexName);
        if (count == null || count == 0) {
            String prefix = qualifier.isBlank() ? "" : qualifier + " ";
            jdbcTemplate.execute("CREATE " + prefix + "INDEX `" + indexName + "` ON `"
                    + tableName + "` (" + columns + ")");
            log.info("[MemoryL2] added index {}.{}", tableName, indexName);
        }
    }

    private void dropIndexIfExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, Integer.class, tableName, indexName);
        if (count != null && count > 0) {
            jdbcTemplate.execute("DROP INDEX `" + indexName + "` ON `" + tableName + "`");
            log.info("[MemoryL2] removed obsolete index {}.{}", tableName, indexName);
        }
    }

    private Map<String, String> recordColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("source_event_id", "varchar(128) DEFAULT NULL");
        columns.put("idempotency_key", "char(64) COLLATE utf8mb4_bin DEFAULT NULL");
        columns.put("payload_hash", "char(64) COLLATE utf8mb4_bin DEFAULT NULL");
        columns.put("source_type", "varchar(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN'");
        columns.put("assertion_status", "varchar(32) NOT NULL DEFAULT 'INFERRED'");
        columns.put("scope_type", "varchar(32) NOT NULL DEFAULT 'USER'");
        columns.put("scope_key", "varchar(128) COLLATE utf8mb4_bin NOT NULL DEFAULT 'USER'");
        columns.put("task_key", "varchar(128) COLLATE utf8mb4_bin DEFAULT NULL");
        columns.put("slot_key", "varchar(128) COLLATE utf8mb4_bin NOT NULL DEFAULT ''");
        columns.put("revision", "bigint NOT NULL DEFAULT 0");
        columns.put("status", "varchar(32) NOT NULL DEFAULT 'QUARANTINED'");
        columns.put("supersedes_id", "bigint DEFAULT NULL");
        columns.put("valid_from", "datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)");
        columns.put("valid_until", "datetime(6) DEFAULT NULL");
        columns.put("deleted_at", "datetime(6) DEFAULT NULL");
        return columns;
    }
}
