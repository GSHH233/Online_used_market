CREATE TABLE IF NOT EXISTS `ai_publish_assist_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assist_id` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `trace_id` varchar(128) NOT NULL,
  `request_json` json NOT NULL,
  `suggestion_json` json NOT NULL,
  `evidence_json` json NOT NULL,
  `risk_level` varchar(16) NOT NULL DEFAULT 'UNKNOWN',
  `model_name` varchar(128) NOT NULL DEFAULT '',
  `prompt_version` varchar(64) NOT NULL DEFAULT '',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_publish_assist_id` (`assist_id`),
  KEY `idx_publish_assist_user_time` (`user_id`, `created_time`),
  KEY `idx_publish_assist_risk_time` (`risk_level`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI发布建议快照';

CREATE TABLE IF NOT EXISTS `ai_publish_assist_feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `assist_id` varchar(64) NOT NULL,
  `product_id` bigint DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `adoption_type` varchar(16) NOT NULL,
  `adopted_fields_json` json NOT NULL,
  `final_content_hash` varchar(64) NOT NULL DEFAULT '',
  `feedback_reason` varchar(500) NOT NULL DEFAULT '',
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_publish_feedback_assist` (`assist_id`),
  KEY `idx_publish_feedback_product` (`product_id`),
  KEY `idx_publish_feedback_user_time` (`user_id`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI发布建议采纳反馈';

CREATE TABLE IF NOT EXISTS `ai_product_risk_outcome` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `assist_id` varchar(64) DEFAULT NULL,
  `outcome_type` varchar(32) NOT NULL,
  `outcome_source` varchar(32) NOT NULL,
  `reason_code` varchar(64) NOT NULL DEFAULT '',
  `reason` varchar(1000) NOT NULL DEFAULT '',
  `operator_id` bigint NOT NULL,
  `is_final` tinyint NOT NULL DEFAULT 0,
  `created_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_risk_outcome_product_time` (`product_id`, `created_time`),
  KEY `idx_risk_outcome_assist` (`assist_id`),
  KEY `idx_risk_outcome_final` (`is_final`, `created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品风险审核和投诉结果';
