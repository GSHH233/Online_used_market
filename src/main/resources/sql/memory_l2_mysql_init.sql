-- Canonical MySQL schema for L2 memory. This script is safe to run repeatedly.
-- Existing installations are upgraded by MemoryL2MySqlInitializer because
-- CREATE TABLE IF NOT EXISTS does not add columns to an existing table.

CREATE TABLE IF NOT EXISTS `ai_l2_memory_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    `session_id` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    `memory_type` VARCHAR(32) NOT NULL,
    `content` TEXT NOT NULL,
    `importance` DOUBLE NOT NULL DEFAULT 0.5,
    `embedding_json` JSON DEFAULT NULL,
    `metadata_json` JSON DEFAULT NULL,
    `source_event_id` VARCHAR(128) DEFAULT NULL,
    `idempotency_key` CHAR(64) COLLATE utf8mb4_bin DEFAULT NULL,
    `payload_hash` CHAR(64) COLLATE utf8mb4_bin DEFAULT NULL,
    `source_type` VARCHAR(32) NOT NULL DEFAULT 'LEGACY_UNKNOWN',
    `assertion_status` VARCHAR(32) NOT NULL DEFAULT 'INFERRED',
    `scope_type` VARCHAR(32) NOT NULL DEFAULT 'USER',
    `scope_key` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT 'USER',
    `task_key` VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL,
    `slot_key` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    `revision` BIGINT NOT NULL DEFAULT 0,
    `status` VARCHAR(32) NOT NULL DEFAULT 'QUARANTINED',
    `supersedes_id` BIGINT DEFAULT NULL,
    `valid_from` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `valid_until` DATETIME(6) DEFAULT NULL,
    `deleted_at` DATETIME(6) DEFAULT NULL,
    `created_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_l2_owner_idempotency` (`user_id`, `idempotency_key`),
    KEY `idx_ai_l2_owner_type_status_time` (`user_id`, `memory_type`, `status`, `updated_time`),
    KEY `idx_ai_l2_owner_session_type_revision` (`user_id`, `session_id`, `memory_type`, `revision`),
    KEY `idx_ai_l2_validity` (`status`, `valid_until`),
    KEY `idx_ai_l2_supersedes` (`supersedes_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `ai_memory_slot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `owner_user_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    `scope_type` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `scope_key` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    `memory_type` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    `slot_key` VARCHAR(128) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    `current_record_id` BIGINT DEFAULT NULL,
    `revision` BIGINT NOT NULL DEFAULT 0,
    `created_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_memory_slot_scope_v2` (`owner_user_id`, `scope_type`, `scope_key`, `memory_type`, `slot_key`),
    KEY `idx_ai_memory_slot_record` (`current_record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
