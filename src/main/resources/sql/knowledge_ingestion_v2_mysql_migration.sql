-- Manual MySQL 8 migration. The application performs the same checks when
-- ai.knowledge.ingestion.auto-init=true.

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing$$
CREATE PROCEDURE add_column_if_missing(
    IN target_table VARCHAR(64),
    IN target_column VARCHAR(64),
    IN column_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = target_table
          AND column_name = target_column
    ) THEN
        SET @ddl = CONCAT(
            'ALTER TABLE `', target_table, '` ADD COLUMN `',
            target_column, '` ', column_definition
        );
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DROP PROCEDURE IF EXISTS add_index_if_missing$$
CREATE PROCEDURE add_index_if_missing(
    IN target_table VARCHAR(64),
    IN target_index VARCHAR(64),
    IN index_columns TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = target_table
          AND index_name = target_index
    ) THEN
        SET @ddl = CONCAT(
            'CREATE INDEX `', target_index, '` ON `',
            target_table, '` (', index_columns, ')'
        );
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_column_if_missing('ai_knowledge_doc', 'file_name', 'varchar(255) DEFAULT NULL');
CALL add_column_if_missing('ai_knowledge_doc', 'mime_type', 'varchar(128) DEFAULT NULL');
CALL add_column_if_missing('ai_knowledge_doc', 'file_hash', 'char(64) DEFAULT NULL');
CALL add_column_if_missing('ai_knowledge_doc', 'parser_type', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('ai_knowledge_doc', 'parser_version', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('ai_knowledge_doc', 'structure_json', 'longtext DEFAULT NULL');
CALL add_column_if_missing('ai_knowledge_doc', 'chunk_strategy_version', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('ai_knowledge_doc', 'embedding_model', 'varchar(128) DEFAULT NULL');
CALL add_column_if_missing('ai_knowledge_doc', 'active_index_version', 'varchar(96) DEFAULT NULL');

CALL add_column_if_missing('ai_knowledge_chunk', 'index_version', 'varchar(96) DEFAULT NULL');
CALL add_column_if_missing('ai_knowledge_chunk', 'index_status', 'varchar(24) DEFAULT NULL');

CALL add_index_if_missing(
    'ai_knowledge_doc',
    'idx_ai_doc_file_hash',
    '`file_hash`, `parser_version`, `chunk_strategy_version`'
);
CALL add_index_if_missing(
    'ai_knowledge_doc',
    'idx_ai_doc_ingestion_version',
    '`file_hash`, `parser_version`, `chunk_strategy_version`, `embedding_model`'
);

DROP PROCEDURE IF EXISTS add_column_if_missing;
DROP PROCEDURE IF EXISTS add_index_if_missing;
