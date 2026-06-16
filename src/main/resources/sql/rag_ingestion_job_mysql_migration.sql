-- RAG Ingestion Job / Repair API 状态表迁移脚本
-- 适用：MySQL 8.x。项目未接入 Flyway/Liquibase，需手动执行。

CREATE TABLE IF NOT EXISTS `ai_knowledge_index_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `doc_id` bigint NOT NULL COMMENT '知识文档ID',
  `task_type` varchar(32) NOT NULL DEFAULT 'REINDEX' COMMENT '任务类型：INGEST/REINDEX/REPAIR',
  `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/QUEUED/RUNNING/SUCCESS/FAILED/DEAD_LETTERED',
  `stage` varchar(64) NOT NULL DEFAULT 'CREATED' COMMENT '阶段：CREATED/CLEANUP/SPLIT/DB_WRITE/EMBEDDING/VECTOR_WRITE/ES_WRITE/VERIFY/FINISHED',
  `async_task` tinyint NOT NULL DEFAULT 0 COMMENT '是否异步任务',
  `force_repair` tinyint NOT NULL DEFAULT 0 COMMENT '是否强制修复',
  `chunk_count` int NOT NULL DEFAULT 0 COMMENT '本次任务生成 chunk 数',
  `db_count` int NOT NULL DEFAULT 0 COMMENT 'DB chunk 数',
  `es_count` int NOT NULL DEFAULT 0 COMMENT 'ES chunk 数',
  `milvus_count` int NOT NULL DEFAULT 0 COMMENT 'Milvus chunk 数',
  `consistent` tinyint NOT NULL DEFAULT 0 COMMENT '三存储是否一致',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '重试次数',
  `elapsed_ms` bigint DEFAULT NULL COMMENT '任务耗时毫秒',
  `error_msg` varchar(1000) DEFAULT NULL COMMENT '错误信息',
  `detail_json` json DEFAULT NULL COMMENT '任务详情/一致性快照',
  `started_at` datetime DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime DEFAULT NULL COMMENT '结束时间',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  KEY `idx_doc_id` (`doc_id`),
  KEY `idx_status` (`status`),
  KEY `idx_task_type` (`task_type`),
  KEY `idx_created_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG知识入库任务表';

DROP PROCEDURE IF EXISTS add_ai_knowledge_index_task_column;
DELIMITER $$
CREATE PROCEDURE add_ai_knowledge_index_task_column(
  IN col_name varchar(64),
  IN ddl text
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_knowledge_index_task'
      AND column_name = col_name
  ) THEN
    SET @sql = ddl;
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL add_ai_knowledge_index_task_column('task_type', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `task_type` varchar(32) NOT NULL DEFAULT ''REINDEX'' COMMENT ''任务类型：INGEST/REINDEX/REPAIR'' AFTER `doc_id`');
CALL add_ai_knowledge_index_task_column('stage', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `stage` varchar(64) NOT NULL DEFAULT ''CREATED'' COMMENT ''阶段'' AFTER `status`');
CALL add_ai_knowledge_index_task_column('async_task', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `async_task` tinyint NOT NULL DEFAULT 0 COMMENT ''是否异步任务'' AFTER `stage`');
CALL add_ai_knowledge_index_task_column('force_repair', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `force_repair` tinyint NOT NULL DEFAULT 0 COMMENT ''是否强制修复'' AFTER `async_task`');
CALL add_ai_knowledge_index_task_column('chunk_count', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `chunk_count` int NOT NULL DEFAULT 0 COMMENT ''本次任务生成 chunk 数'' AFTER `force_repair`');
CALL add_ai_knowledge_index_task_column('db_count', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `db_count` int NOT NULL DEFAULT 0 COMMENT ''DB chunk 数'' AFTER `chunk_count`');
CALL add_ai_knowledge_index_task_column('es_count', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `es_count` int NOT NULL DEFAULT 0 COMMENT ''ES chunk 数'' AFTER `db_count`');
CALL add_ai_knowledge_index_task_column('milvus_count', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `milvus_count` int NOT NULL DEFAULT 0 COMMENT ''Milvus chunk 数'' AFTER `es_count`');
CALL add_ai_knowledge_index_task_column('consistent', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `consistent` tinyint NOT NULL DEFAULT 0 COMMENT ''三存储是否一致'' AFTER `milvus_count`');
CALL add_ai_knowledge_index_task_column('retry_count', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `retry_count` int NOT NULL DEFAULT 0 COMMENT ''重试次数'' AFTER `consistent`');
CALL add_ai_knowledge_index_task_column('elapsed_ms', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `elapsed_ms` bigint DEFAULT NULL COMMENT ''任务耗时毫秒'' AFTER `retry_count`');
CALL add_ai_knowledge_index_task_column('detail_json', 'ALTER TABLE `ai_knowledge_index_task` ADD COLUMN `detail_json` json DEFAULT NULL COMMENT ''任务详情/一致性快照'' AFTER `error_msg`');

DROP PROCEDURE IF EXISTS add_ai_knowledge_index_task_column;

SET @idx_doc_status = (
  SELECT IF(COUNT(*) = 0,
            'CREATE INDEX `idx_ai_kit_doc_status` ON `ai_knowledge_index_task` (`doc_id`, `status`)',
            'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ai_knowledge_index_task'
    AND index_name = 'idx_ai_kit_doc_status'
);
PREPARE stmt FROM @idx_doc_status;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_type_created = (
  SELECT IF(COUNT(*) = 0,
            'CREATE INDEX `idx_ai_kit_type_created` ON `ai_knowledge_index_task` (`task_type`, `created_time`)',
            'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ai_knowledge_index_task'
    AND index_name = 'idx_ai_kit_type_created'
);
PREPARE stmt FROM @idx_type_created;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
