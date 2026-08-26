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

CREATE TABLE IF NOT EXISTS ai_token_usage (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    scene VARCHAR(64) NOT NULL DEFAULT 'GENERAL',
    model VARCHAR(128) NOT NULL DEFAULT '',
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens INTEGER NOT NULL DEFAULT 0,
    input_budget INTEGER NOT NULL DEFAULT 0,
    output_budget INTEGER NOT NULL DEFAULT 0,
    original_prompt_tokens INTEGER NOT NULL DEFAULT 0,
    trimmed_tokens INTEGER NOT NULL DEFAULT 0,
    usage_estimated BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INTEGER NOT NULL DEFAULT 1,
    elapsed_ms BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    error_message TEXT NOT NULL DEFAULT '',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_token_usage_trace
    ON ai_token_usage (trace_id, created_time DESC);
CREATE INDEX IF NOT EXISTS idx_ai_token_usage_scene
    ON ai_token_usage (scene, created_time DESC);

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
    fingerprint VARCHAR(64) NOT NULL DEFAULT '',
    correlation_key VARCHAR(160) NOT NULL DEFAULT '',
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    severity VARCHAR(32) NOT NULL DEFAULT 'INFO',
    source VARCHAR(64) NOT NULL DEFAULT '',
    title VARCHAR(255) NOT NULL DEFAULT '',
    summary TEXT NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    approved_by BIGINT,
    approved_at TIMESTAMP,
    resolved_at TIMESTAMP,
    resolution_type VARCHAR(32) NOT NULL DEFAULT '',
    manual_required BOOLEAN NOT NULL DEFAULT FALSE,
    escalation_reason TEXT NOT NULL DEFAULT '',
    source_started_at TIMESTAMP,
    source_ended_at TIMESTAMP,
    diagnosis_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    diagnosis_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    operation_id BIGINT,
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
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    idempotency_key VARCHAR(160) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'SUGGESTED',
    verification_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    suggestion TEXT NOT NULL DEFAULT '',
    executed BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_aiops_action_event
    ON aiops_action (event_id);

ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS approved_by BIGINT;
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP;
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP;
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS resolution_type VARCHAR(32) NOT NULL DEFAULT '';
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS manual_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS escalation_reason TEXT NOT NULL DEFAULT '';
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS correlation_key VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS occurrence_count INTEGER NOT NULL DEFAULT 1;
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS source_started_at TIMESTAMP;
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS source_ended_at TIMESTAMP;
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS diagnosis_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS diagnosis_json JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE aiops_event ADD COLUMN IF NOT EXISTS operation_id BIGINT;

ALTER TABLE aiops_action ADD COLUMN IF NOT EXISTS trace_id VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE aiops_action ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE aiops_action ADD COLUMN IF NOT EXISTS verification_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE aiops_action ADD COLUMN IF NOT EXISTS started_at TIMESTAMP;
ALTER TABLE aiops_action ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uk_aiops_action_idempotency
    ON aiops_action (event_id, action_type, idempotency_key)
    WHERE idempotency_key <> '';
CREATE INDEX IF NOT EXISTS idx_aiops_event_resolved
    ON aiops_event (resolved_at DESC)
    WHERE resolved_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_aiops_event_correlation
    ON aiops_event (correlation_key, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_aiops_event_fingerprint
    ON aiops_event (fingerprint)
    WHERE fingerprint <> '';
CREATE UNIQUE INDEX IF NOT EXISTS uk_aiops_event_fingerprint
    ON aiops_event (fingerprint)
    WHERE fingerprint <> '';

CREATE TABLE IF NOT EXISTS aiops_alert_delivery (
    id BIGSERIAL PRIMARY KEY,
    delivery_key VARCHAR(160) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    group_key VARCHAR(160) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'FIRING',
    alert_count INTEGER NOT NULL DEFAULT 0,
    processing_status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    error_msg TEXT NOT NULL DEFAULT '',
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    event_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(delivery_key),
    UNIQUE(payload_hash)
);

CREATE INDEX IF NOT EXISTS idx_aiops_alert_delivery_status
    ON aiops_alert_delivery (processing_status, created_time DESC);
CREATE INDEX IF NOT EXISTS idx_aiops_alert_delivery_group
    ON aiops_alert_delivery (group_key, created_time DESC);

CREATE TABLE IF NOT EXISTS aiops_change_event (
    id BIGSERIAL PRIMARY KEY,
    service VARCHAR(128) NOT NULL,
    environment VARCHAR(64) NOT NULL DEFAULT '',
    change_type VARCHAR(64) NOT NULL,
    version VARCHAR(128) NOT NULL DEFAULT '',
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    summary TEXT NOT NULL DEFAULT '',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_aiops_change_event_service_time
    ON aiops_change_event (service, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_aiops_change_event_trace
    ON aiops_change_event (trace_id);

CREATE TABLE IF NOT EXISTS ai_rag_chat_turn_audit (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL DEFAULT '',
    query TEXT NOT NULL DEFAULT '',
    refused BOOLEAN NOT NULL DEFAULT FALSE,
    reason_code VARCHAR(32) NOT NULL DEFAULT '',
    low_confidence BOOLEAN NOT NULL DEFAULT FALSE,
    source_count INTEGER NOT NULL DEFAULT 0,
    retrieval_mode VARCHAR(32) NOT NULL DEFAULT '',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(trace_id)
);

CREATE INDEX IF NOT EXISTS idx_rag_turn_audit_created
    ON ai_rag_chat_turn_audit (created_time DESC);

CREATE TABLE IF NOT EXISTS ai_knowledge_gap (
    id BIGSERIAL PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    query TEXT NOT NULL DEFAULT '',
    normalized_query TEXT NOT NULL DEFAULT '',
    reason_code VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    recurrence_count INTEGER NOT NULL DEFAULT 0,
    linked_doc_id BIGINT,
    linked_task_id BIGINT,
    latest_trace_id VARCHAR(128) NOT NULL DEFAULT '',
    latest_session_id VARCHAR(128) NOT NULL DEFAULT '',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(fingerprint, reason_code)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_gap_status_priority
    ON ai_knowledge_gap (status, priority, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_gap_doc
    ON ai_knowledge_gap (linked_doc_id);

CREATE TABLE IF NOT EXISTS ai_knowledge_gap_regression (
    id BIGSERIAL PRIMARY KEY,
    gap_id BIGINT NOT NULL REFERENCES ai_knowledge_gap(id) ON DELETE CASCADE,
    linked_doc_id BIGINT,
    linked_task_id BIGINT,
    retrieval_hit BOOLEAN NOT NULL DEFAULT FALSE,
    source_hit BOOLEAN NOT NULL DEFAULT FALSE,
    reject_resolved BOOLEAN NOT NULL DEFAULT FALSE,
    passed BOOLEAN NOT NULL DEFAULT FALSE,
    latency_ms BIGINT,
    eval_run_id BIGINT REFERENCES ai_eval_run(id) ON DELETE SET NULL,
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    error_msg TEXT NOT NULL DEFAULT '',
    details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_gap_regression_gap_time
    ON ai_knowledge_gap_regression (gap_id, created_time DESC);

CREATE TABLE IF NOT EXISTS agent_runtime_run (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL UNIQUE,
    trace_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL DEFAULT '',
    strategy VARCHAR(32) NOT NULL DEFAULT 'DAG',
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    question TEXT NOT NULL DEFAULT '',
    max_steps INTEGER NOT NULL DEFAULT 6,
    fault_after_nodes INTEGER NOT NULL DEFAULT 0,
    plan_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    answer TEXT NOT NULL DEFAULT '',
    error_msg TEXT NOT NULL DEFAULT '',
    version INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128) NOT NULL DEFAULT '',
    lease_until TIMESTAMP,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP
);
ALTER TABLE agent_runtime_run ADD COLUMN IF NOT EXISTS fault_after_nodes INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_runtime_run_status_lease ON agent_runtime_run(status, lease_until);
CREATE INDEX IF NOT EXISTS idx_runtime_run_trace ON agent_runtime_run(trace_id);

CREATE TABLE IF NOT EXISTS agent_runtime_node (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL REFERENCES agent_runtime_run(run_id) ON DELETE CASCADE,
    node_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL DEFAULT '',
    node_type VARCHAR(32) NOT NULL DEFAULT '',
    dependencies_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    input_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    observation_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    elapsed_ms BIGINT,
    error_msg TEXT NOT NULL DEFAULT '',
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    UNIQUE(run_id, node_id)
);
CREATE INDEX IF NOT EXISTS idx_runtime_node_run ON agent_runtime_node(run_id, status);

CREATE TABLE IF NOT EXISTS agent_runtime_checkpoint (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL REFERENCES agent_runtime_run(run_id) ON DELETE CASCADE,
    checkpoint_seq INTEGER NOT NULL,
    reason VARCHAR(64) NOT NULL DEFAULT '',
    state_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    state_hash VARCHAR(64) NOT NULL DEFAULT '',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(run_id, checkpoint_seq)
);
CREATE INDEX IF NOT EXISTS idx_runtime_checkpoint_run ON agent_runtime_checkpoint(run_id, checkpoint_seq DESC);

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

DROP TRIGGER IF EXISTS trg_runtime_run_updated ON agent_runtime_run;
CREATE TRIGGER trg_runtime_run_updated
BEFORE UPDATE ON agent_runtime_run
FOR EACH ROW EXECUTE FUNCTION set_updated_time_ai_observability();
