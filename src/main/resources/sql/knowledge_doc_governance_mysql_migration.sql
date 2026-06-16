-- RAG 知识库来源治理字段迁移脚本
-- 适用：MySQL 8.x。项目未接入 Flyway/Liquibase，需手动执行。

DROP PROCEDURE IF EXISTS add_ai_knowledge_doc_column;
DELIMITER $$
CREATE PROCEDURE add_ai_knowledge_doc_column(
  IN col_name varchar(64),
  IN ddl text
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_knowledge_doc'
      AND column_name = col_name
  ) THEN
    SET @sql = ddl;
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL add_ai_knowledge_doc_column('source_trust_level', 'ALTER TABLE `ai_knowledge_doc` ADD COLUMN `source_trust_level` varchar(32) NOT NULL DEFAULT ''MEDIUM'' COMMENT ''来源可信等级 HIGH/MEDIUM/LOW/UNKNOWN'' AFTER `tags`');
CALL add_ai_knowledge_doc_column('effective_from', 'ALTER TABLE `ai_knowledge_doc` ADD COLUMN `effective_from` datetime DEFAULT NULL COMMENT ''生效开始时间'' AFTER `source_trust_level`');
CALL add_ai_knowledge_doc_column('effective_to', 'ALTER TABLE `ai_knowledge_doc` ADD COLUMN `effective_to` datetime DEFAULT NULL COMMENT ''生效结束时间'' AFTER `effective_from`');
CALL add_ai_knowledge_doc_column('doc_version', 'ALTER TABLE `ai_knowledge_doc` ADD COLUMN `doc_version` varchar(64) NOT NULL DEFAULT ''v1'' COMMENT ''文档版本'' AFTER `effective_to`');
CALL add_ai_knowledge_doc_column('disabled_reason', 'ALTER TABLE `ai_knowledge_doc` ADD COLUMN `disabled_reason` varchar(500) DEFAULT NULL COMMENT ''禁用原因'' AFTER `doc_version`');

DROP PROCEDURE IF EXISTS add_ai_knowledge_doc_column;

SET @idx_doc_governance = (
  SELECT IF(COUNT(*) = 0,
            'CREATE INDEX `idx_ai_doc_governance` ON `ai_knowledge_doc` (`status`, `source_trust_level`, `effective_to`)',
            'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ai_knowledge_doc'
    AND index_name = 'idx_ai_doc_governance'
);
PREPARE stmt FROM @idx_doc_governance;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
