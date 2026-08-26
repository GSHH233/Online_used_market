-- Delayed cleanup and rollback metadata for managed knowledge revisions.
-- Safe to run repeatedly on MySQL 8. The runtime initializer applies the same schema checks.

DELIMITER $$

DROP PROCEDURE IF EXISTS add_lifecycle_column_if_missing$$
CREATE PROCEDURE add_lifecycle_column_if_missing(
    IN target_column VARCHAR(64),
    IN column_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_knowledge_doc'
          AND column_name = target_column
    ) THEN
        SET @ddl = CONCAT(
            'ALTER TABLE `ai_knowledge_doc` ADD COLUMN `',
            target_column, '` ', column_definition
        );
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_lifecycle_column_if_missing('retired_at', 'datetime(6) DEFAULT NULL');
CALL add_lifecycle_column_if_missing('retention_until', 'datetime(6) DEFAULT NULL');
CALL add_lifecycle_column_if_missing('cleanup_status', 'varchar(32) DEFAULT NULL');
CALL add_lifecycle_column_if_missing('cleanup_error', 'varchar(500) DEFAULT NULL');
CALL add_lifecycle_column_if_missing('cleaned_at', 'datetime(6) DEFAULT NULL');

SET @idx_lifecycle_cleanup = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_knowledge_doc'
      AND index_name = 'idx_ai_doc_retired_cleanup'
);
SET @ddl_lifecycle_cleanup = IF(
    @idx_lifecycle_cleanup = 0,
    'CREATE INDEX `idx_ai_doc_retired_cleanup` ON `ai_knowledge_doc` (`status`, `retention_until`, `cleanup_status`)',
    'SELECT 1'
);
PREPARE stmt_lifecycle_cleanup FROM @ddl_lifecycle_cleanup;
EXECUTE stmt_lifecycle_cleanup;
DEALLOCATE PREPARE stmt_lifecycle_cleanup;

DROP PROCEDURE IF EXISTS add_lifecycle_column_if_missing;
