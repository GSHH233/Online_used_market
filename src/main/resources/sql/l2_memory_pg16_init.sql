-- PostgreSQL 16 初始化脚本：L2 持久记忆表
-- 建议库: used_market

CREATE TABLE IF NOT EXISTS ai_l2_memory_record (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL DEFAULT '',
    memory_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    importance DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    embedding_json TEXT NOT NULL DEFAULT '[]',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_l2_user_updated
    ON ai_l2_memory_record (user_id, updated_time DESC);

CREATE INDEX IF NOT EXISTS idx_l2_session_updated
    ON ai_l2_memory_record (session_id, updated_time DESC);

CREATE INDEX IF NOT EXISTS idx_l2_type
    ON ai_l2_memory_record (memory_type);

-- 文本兜底检索索引（PG内置全文）
CREATE INDEX IF NOT EXISTS idx_l2_content_tsv
    ON ai_l2_memory_record
    USING GIN (to_tsvector('simple', content));

-- updated_time 自动维护触发器
CREATE OR REPLACE FUNCTION set_updated_time_ai_l2_memory_record()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_time = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_set_updated_time_ai_l2_memory_record ON ai_l2_memory_record;
CREATE TRIGGER trg_set_updated_time_ai_l2_memory_record
BEFORE UPDATE ON ai_l2_memory_record
FOR EACH ROW
EXECUTE FUNCTION set_updated_time_ai_l2_memory_record();
