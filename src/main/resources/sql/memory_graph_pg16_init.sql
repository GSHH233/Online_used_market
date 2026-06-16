-- PostgreSQL 16 初始化脚本：长期 Memory Graph
-- 建议库: used_market

CREATE TABLE IF NOT EXISTS ai_memory_node (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL DEFAULT '',
    node_type VARCHAR(32) NOT NULL,
    label VARCHAR(160) NOT NULL,
    content TEXT NOT NULL DEFAULT '',
    topic VARCHAR(64) NOT NULL DEFAULT '',
    importance DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.8,
    source VARCHAR(64) NOT NULL DEFAULT '',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_memory_node_user_label_type
    ON ai_memory_node (user_id, node_type, label);

CREATE INDEX IF NOT EXISTS idx_memory_node_user_topic
    ON ai_memory_node (user_id, topic);

CREATE INDEX IF NOT EXISTS idx_memory_node_updated
    ON ai_memory_node (user_id, updated_time DESC);

CREATE TABLE IF NOT EXISTS ai_memory_edge (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL DEFAULT '',
    from_node_id BIGINT NOT NULL REFERENCES ai_memory_node(id) ON DELETE CASCADE,
    to_node_id BIGINT NOT NULL REFERENCES ai_memory_node(id) ON DELETE CASCADE,
    edge_type VARCHAR(64) NOT NULL,
    weight DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.8,
    evidence TEXT NOT NULL DEFAULT '',
    source VARCHAR(64) NOT NULL DEFAULT '',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_memory_edge_user_from_to_type
    ON ai_memory_edge (user_id, from_node_id, to_node_id, edge_type);

CREATE INDEX IF NOT EXISTS idx_memory_edge_user_from
    ON ai_memory_edge (user_id, from_node_id);

CREATE INDEX IF NOT EXISTS idx_memory_edge_user_to
    ON ai_memory_edge (user_id, to_node_id);

CREATE INDEX IF NOT EXISTS idx_memory_edge_updated
    ON ai_memory_edge (user_id, updated_time DESC);

CREATE OR REPLACE FUNCTION set_updated_time_ai_memory_graph()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_time = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_set_updated_time_ai_memory_node ON ai_memory_node;
CREATE TRIGGER trg_set_updated_time_ai_memory_node
BEFORE UPDATE ON ai_memory_node
FOR EACH ROW
EXECUTE FUNCTION set_updated_time_ai_memory_graph();

DROP TRIGGER IF EXISTS trg_set_updated_time_ai_memory_edge ON ai_memory_edge;
CREATE TRIGGER trg_set_updated_time_ai_memory_edge
BEFORE UPDATE ON ai_memory_edge
FOR EACH ROW
EXECUTE FUNCTION set_updated_time_ai_memory_graph();
