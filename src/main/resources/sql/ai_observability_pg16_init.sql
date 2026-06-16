-- PostgreSQL 16 初始化脚本：AI Observability / Trace / Eval / AIOps
-- 建议库: used_market

CREATE TABLE IF NOT EXISTS ai_agent_trace_run (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL DEFAULT '',
    agent_type VARCHAR(32) NOT NULL,
    mode VARCHAR(32) NOT NULL DEFAULT '',
    question TEXT NOT NULL DEFAULT '',
    answer TEXT NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    success BOOLEAN NOT NULL DEFAULT TRUE,
    elapsed_ms BIGINT,
    step_count INTEGER NOT NULL DEFAULT 0,
    observation_count INTEGER NOT NULL DEFAULT 0,
    source_count INTEGER NOT NULL DEFAULT 0,
    tools TEXT NOT NULL DEFAULT '',
    debug_info TEXT NOT NULL DEFAULT '',
    error_msg TEXT NOT NULL DEFAULT '',
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_trace_run_trace
    ON ai_agent_trace_run (trace_id);
CREATE INDEX IF NOT EXISTS idx_ai_agent_trace_run_session
    ON ai_agent_trace_run (session_id);
CREATE INDEX IF NOT EXISTS idx_ai_agent_trace_run_type_status
    ON ai_agent_trace_run (agent_type, status);
CREATE INDEX IF NOT EXISTS idx_ai_agent_trace_run_created
    ON ai_agent_trace_run (created_time DESC);

CREATE TABLE IF NOT EXISTS ai_agent_trace_step (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES ai_agent_trace_run(id) ON DELETE CASCADE,
    trace_id VARCHAR(128) NOT NULL,
    step_index INTEGER NOT NULL DEFAULT 0,
    phase VARCHAR(64) NOT NULL DEFAULT '',
    tool VARCHAR(128) NOT NULL DEFAULT '',
    status VARCHAR(64) NOT NULL DEFAULT '',
    thought TEXT NOT NULL DEFAULT '',
    action TEXT NOT NULL DEFAULT '',
    observation TEXT NOT NULL DEFAULT '',
    elapsed_ms BIGINT,
    source_doc_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_agent_trace_step_run
    ON ai_agent_trace_step (run_id, step_index);
CREATE INDEX IF NOT EXISTS idx_ai_agent_trace_step_trace
    ON ai_agent_trace_step (trace_id);
CREATE INDEX IF NOT EXISTS idx_ai_agent_trace_step_tool
    ON ai_agent_trace_step (tool);
CREATE INDEX IF NOT EXISTS idx_ai_agent_trace_step_status
    ON ai_agent_trace_step (status);

CREATE TABLE IF NOT EXISTS ai_eval_run (
    id BIGSERIAL PRIMARY KEY,
    eval_type VARCHAR(64) NOT NULL,
    report_file VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    total_cases INTEGER,
    passed_cases INTEGER,
    failed_cases INTEGER,
    success_rate DOUBLE PRECISION,
    p50_ms DOUBLE PRECISION,
    p95_ms DOUBLE PRECISION,
    metrics_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    preview TEXT NOT NULL DEFAULT '',
    report_mtime TIMESTAMP,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(report_file)
);

CREATE INDEX IF NOT EXISTS idx_ai_eval_run_type_updated
    ON ai_eval_run (eval_type, updated_time DESC);

CREATE TABLE IF NOT EXISTS ai_eval_metric (
    id BIGSERIAL PRIMARY KEY,
    eval_run_id BIGINT NOT NULL REFERENCES ai_eval_run(id) ON DELETE CASCADE,
    metric_name VARCHAR(160) NOT NULL,
    metric_value TEXT NOT NULL DEFAULT '',
    metric_numeric DOUBLE PRECISION,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_eval_metric_run
    ON ai_eval_metric (eval_run_id);

CREATE TABLE IF NOT EXISTS ai_eval_case_result (
    id BIGSERIAL PRIMARY KEY,
    eval_run_id BIGINT NOT NULL REFERENCES ai_eval_run(id) ON DELETE CASCADE,
    case_id VARCHAR(128) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT '',
    latency_ms DOUBLE PRECISION,
    details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_eval_case_run
    ON ai_eval_case_result (eval_run_id);

CREATE TABLE IF NOT EXISTS aiops_event (
    id BIGSERIAL PRIMARY KEY,
    event_key VARCHAR(160) NOT NULL,
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    severity VARCHAR(32) NOT NULL DEFAULT 'INFO',
    source VARCHAR(64) NOT NULL DEFAULT '',
    title VARCHAR(255) NOT NULL DEFAULT '',
    summary TEXT NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(event_key)
);

CREATE INDEX IF NOT EXISTS idx_aiops_event_status
    ON aiops_event (status, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_aiops_event_trace
    ON aiops_event (trace_id);

CREATE TABLE IF NOT EXISTS aiops_action (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES aiops_event(id) ON DELETE CASCADE,
    action_type VARCHAR(64) NOT NULL DEFAULT 'SUGGESTION',
    status VARCHAR(32) NOT NULL DEFAULT 'SUGGESTED',
    suggestion TEXT NOT NULL DEFAULT '',
    executed BOOLEAN NOT NULL DEFAULT FALSE,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_aiops_action_event
    ON aiops_action (event_id);

CREATE OR REPLACE FUNCTION set_updated_time_ai_observability()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_time = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ai_agent_trace_run_updated ON ai_agent_trace_run;
CREATE TRIGGER trg_ai_agent_trace_run_updated
BEFORE UPDATE ON ai_agent_trace_run
FOR EACH ROW EXECUTE FUNCTION set_updated_time_ai_observability();

DROP TRIGGER IF EXISTS trg_ai_agent_trace_step_updated ON ai_agent_trace_step;
CREATE TRIGGER trg_ai_agent_trace_step_updated
BEFORE UPDATE ON ai_agent_trace_step
FOR EACH ROW EXECUTE FUNCTION set_updated_time_ai_observability();

DROP TRIGGER IF EXISTS trg_ai_eval_run_updated ON ai_eval_run;
CREATE TRIGGER trg_ai_eval_run_updated
BEFORE UPDATE ON ai_eval_run
FOR EACH ROW EXECUTE FUNCTION set_updated_time_ai_observability();

DROP TRIGGER IF EXISTS trg_aiops_event_updated ON aiops_event;
CREATE TRIGGER trg_aiops_event_updated
BEFORE UPDATE ON aiops_event
FOR EACH ROW EXECUTE FUNCTION set_updated_time_ai_observability();

DROP TRIGGER IF EXISTS trg_aiops_action_updated ON aiops_action;
CREATE TRIGGER trg_aiops_action_updated
BEFORE UPDATE ON aiops_action
FOR EACH ROW EXECUTE FUNCTION set_updated_time_ai_observability();
