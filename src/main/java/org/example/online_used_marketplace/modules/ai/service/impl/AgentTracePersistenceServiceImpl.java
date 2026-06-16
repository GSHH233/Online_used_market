package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.AgentTraceQueryDTO;
import org.example.online_used_marketplace.modules.ai.dto.GraphAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ReactAgentRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AgentTracePersistenceService;
import org.example.online_used_marketplace.modules.ai.vo.AgentTraceRunVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentTraceStepPersistVO;
import org.example.online_used_marketplace.modules.ai.vo.AgentTraceStepVO;
import org.example.online_used_marketplace.modules.ai.vo.GraphAgentResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ReactAgentResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class AgentTracePersistenceServiceImpl implements AgentTracePersistenceService {

    private static final int TEXT_LIMIT = 4000;
    private static final int JSON_LIMIT = 12000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentTracePersistenceServiceImpl(
            @Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(transactionManager = "memoryGraphTransactionManager", rollbackFor = Exception.class)
    public void saveReactTrace(ReactAgentRequestDTO request, ReactAgentResponseVO response, long startedMs) {
        if (response == null || !StringUtils.hasText(response.getTraceId())) {
            return;
        }
        saveRunAndSteps(
                response.getTraceId(),
                request == null ? null : request.getSessionId(),
                "REACT",
                response.getMode(),
                response.getQuestion(),
                response.getAnswer(),
                Boolean.TRUE.equals(response.getSuccess()),
                response.getDebugInfo(),
                "",
                response.getAgentTrace(),
                response.getObservations(),
                response.getSources() == null ? 0 : response.getSources().size(),
                startedMs,
                null
        );
    }

    @Override
    @Transactional(transactionManager = "memoryGraphTransactionManager", rollbackFor = Exception.class)
    public void saveGraphTrace(GraphAgentRequestDTO request, GraphAgentResponseVO response, long startedMs) {
        if (response == null || !StringUtils.hasText(response.getTraceId())) {
            return;
        }
        saveRunAndSteps(
                response.getTraceId(),
                request == null ? null : request.getSessionId(),
                "GRAPH",
                response.getMode(),
                response.getQuestion(),
                response.getAnswer(),
                Boolean.TRUE.equals(response.getSuccess()),
                response.getDebugInfo(),
                "",
                response.getAgentTrace(),
                response.getObservations(),
                response.getSources() == null ? 0 : response.getSources().size(),
                startedMs,
                response.getElapsedMs()
        );
    }

    @Override
    @Transactional(transactionManager = "memoryGraphTransactionManager", rollbackFor = Exception.class)
    public void saveReactFailure(ReactAgentRequestDTO request, Throwable error, long startedMs) {
        saveFailure("REACT",
                request == null ? null : request.getTraceId(),
                request == null ? null : request.getSessionId(),
                request == null ? null : request.getMode(),
                request == null ? null : request.getQuestion(),
                error,
                startedMs);
    }

    @Override
    @Transactional(transactionManager = "memoryGraphTransactionManager", rollbackFor = Exception.class)
    public void saveGraphFailure(GraphAgentRequestDTO request, Throwable error, long startedMs) {
        saveFailure("GRAPH",
                request == null ? null : request.getTraceId(),
                request == null ? null : request.getSessionId(),
                request == null ? null : request.getMode(),
                request == null ? null : request.getQuestion(),
                error,
                startedMs);
    }

    @Override
    public AgentTraceRunVO getByTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "traceId不能为空");
        }
        List<AgentTraceRunVO> runs = jdbcTemplate.query("""
                SELECT id,trace_id,session_id,agent_type,mode,question,answer,status,success,elapsed_ms,
                       step_count,observation_count,source_count,tools,debug_info,started_at,finished_at,created_time
                FROM ai_agent_trace_run
                WHERE trace_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, (rs, rowNum) -> {
            AgentTraceRunVO vo = new AgentTraceRunVO();
            vo.setId(rs.getLong("id"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setSessionId(rs.getString("session_id"));
            vo.setAgentType(rs.getString("agent_type"));
            vo.setMode(rs.getString("mode"));
            vo.setQuestion(rs.getString("question"));
            vo.setAnswer(rs.getString("answer"));
            vo.setStatus(rs.getString("status"));
            vo.setSuccess(rs.getBoolean("success"));
            vo.setElapsedMs(rs.getLong("elapsed_ms"));
            vo.setStepCount(rs.getInt("step_count"));
            vo.setObservationCount(rs.getInt("observation_count"));
            vo.setSourceCount(rs.getInt("source_count"));
            vo.setTools(rs.getString("tools"));
            vo.setDebugInfo(rs.getString("debug_info"));
            vo.setStartedAt(toLocalDateTime(rs.getTimestamp("started_at")));
            vo.setFinishedAt(toLocalDateTime(rs.getTimestamp("finished_at")));
            vo.setCreatedTime(toLocalDateTime(rs.getTimestamp("created_time")));
            return vo;
        }, traceId.trim());
        if (runs.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Agent Trace不存在");
        }
        AgentTraceRunVO run = runs.get(0);
        run.setSteps(loadSteps(run.getId()));
        return run;
    }

    @Override
    public List<AgentTraceRunVO> list(AgentTraceQueryDTO query) {
        AgentTraceQueryDTO q = query == null ? new AgentTraceQueryDTO() : query;
        StringBuilder sql = new StringBuilder("""
                SELECT id,trace_id,session_id,agent_type,mode,question,answer,status,success,elapsed_ms,
                       step_count,observation_count,source_count,tools,debug_info,started_at,finished_at,created_time
                FROM ai_agent_trace_run
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(q.getTraceId())) {
            sql.append(" AND trace_id = ?");
            args.add(q.getTraceId().trim());
        }
        if (StringUtils.hasText(q.getSessionId())) {
            sql.append(" AND session_id = ?");
            args.add(q.getSessionId().trim());
        }
        if (StringUtils.hasText(q.getAgentType())) {
            sql.append(" AND agent_type = ?");
            args.add(q.getAgentType().trim().toUpperCase());
        }
        if (StringUtils.hasText(q.getMode())) {
            sql.append(" AND mode = ?");
            args.add(q.getMode().trim().toUpperCase());
        }
        if (StringUtils.hasText(q.getStatus())) {
            sql.append(" AND status = ?");
            args.add(q.getStatus().trim().toUpperCase());
        }
        if (StringUtils.hasText(q.getTool())) {
            sql.append(" AND tools ILIKE ?");
            args.add("%" + q.getTool().trim() + "%");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(q.getLimit() == null ? 20 : q.getLimit(), 100)));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            AgentTraceRunVO vo = new AgentTraceRunVO();
            vo.setId(rs.getLong("id"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setSessionId(rs.getString("session_id"));
            vo.setAgentType(rs.getString("agent_type"));
            vo.setMode(rs.getString("mode"));
            vo.setQuestion(rs.getString("question"));
            vo.setAnswer(rs.getString("answer"));
            vo.setStatus(rs.getString("status"));
            vo.setSuccess(rs.getBoolean("success"));
            vo.setElapsedMs(rs.getLong("elapsed_ms"));
            vo.setStepCount(rs.getInt("step_count"));
            vo.setObservationCount(rs.getInt("observation_count"));
            vo.setSourceCount(rs.getInt("source_count"));
            vo.setTools(rs.getString("tools"));
            vo.setDebugInfo(rs.getString("debug_info"));
            vo.setStartedAt(toLocalDateTime(rs.getTimestamp("started_at")));
            vo.setFinishedAt(toLocalDateTime(rs.getTimestamp("finished_at")));
            vo.setCreatedTime(toLocalDateTime(rs.getTimestamp("created_time")));
            return vo;
        }, args.toArray());
    }

    @Override
    public Map<String, Object> stats() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT agent_type,status,success,elapsed_ms
                FROM ai_agent_trace_run
                ORDER BY id DESC
                LIMIT 200
                """);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recentRuns", rows.size());
        out.put("successRuns", rows.stream().filter(one -> Boolean.TRUE.equals(one.get("success"))).count());
        out.put("failedRuns", rows.stream().filter(one -> !Boolean.TRUE.equals(one.get("success"))).count());
        double avg = rows.stream()
                .map(one -> one.get("elapsed_ms"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToLong(Number::longValue)
                .average()
                .orElse(0D);
        out.put("avgElapsedMs", avg);
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String type = String.valueOf(row.getOrDefault("agent_type", "UNKNOWN"));
            byType.put(type, byType.getOrDefault(type, 0L) + 1);
        }
        out.put("byAgentType", byType);
        return out;
    }

    private void saveFailure(String agentType, String traceId, String sessionId, String mode, String question, Throwable error, long startedMs) {
        String safeTraceId = StringUtils.hasText(traceId) ? traceId.trim() : "failed-" + UUID.randomUUID();
        String msg = error == null ? "unknown error" : error.getClass().getSimpleName() + ": " + error.getMessage();
        saveRunAndSteps(safeTraceId, sessionId, agentType, mode, question, "", false, "", msg,
                List.of(), List.of(), 0, startedMs, null);
    }

    private void saveRunAndSteps(String traceId,
                                 String sessionId,
                                 String agentType,
                                 String mode,
                                 String question,
                                 String answer,
                                 boolean success,
                                 String debugInfo,
                                 String errorMsg,
                                 List<AgentTraceStepVO> traceSteps,
                                 List<ToolQueryResultVO> observations,
                                 int sourceCount,
                                 long startedMs,
                                 Long responseElapsedMs) {
        LocalDateTime startedAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(startedMs), ZoneId.systemDefault());
        LocalDateTime finishedAt = LocalDateTime.now();
        long elapsed = responseElapsedMs != null ? responseElapsedMs : Math.max(0, System.currentTimeMillis() - startedMs);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO ai_agent_trace_run(
                        trace_id,session_id,agent_type,mode,question,answer,status,success,elapsed_ms,
                        step_count,observation_count,source_count,tools,debug_info,error_msg,started_at,finished_at,metadata_json
                    )
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, traceId);
            ps.setString(2, safe(sessionId, 128));
            ps.setString(3, agentType);
            ps.setString(4, safe(mode, 32));
            ps.setString(5, safe(question, TEXT_LIMIT));
            ps.setString(6, safe(answer, TEXT_LIMIT));
            ps.setString(7, success ? "SUCCESS" : "FAILED");
            ps.setBoolean(8, success);
            ps.setLong(9, elapsed);
            ps.setInt(10, traceSteps == null ? 0 : traceSteps.size());
            ps.setInt(11, observations == null ? 0 : observations.size());
            ps.setInt(12, sourceCount);
            ps.setString(13, joinTools(observations));
            ps.setString(14, safe(debugInfo, 1000));
            ps.setString(15, safe(errorMsg, 1000));
            ps.setTimestamp(16, Timestamp.valueOf(startedAt));
            ps.setTimestamp(17, Timestamp.valueOf(finishedAt));
            ps.setString(18, toJson(Map.of("storage", "pgsql"), JSON_LIMIT));
            return ps;
        }, keyHolder);
        Long runId = generatedId(keyHolder);
        if (runId == null || traceSteps == null) {
            return;
        }
        for (int i = 0; i < traceSteps.size(); i++) {
            AgentTraceStepVO step = traceSteps.get(i);
            jdbcTemplate.update("""
                    INSERT INTO ai_agent_trace_step(
                        run_id,trace_id,step_index,phase,tool,status,thought,action,observation,elapsed_ms,source_doc_ids_json,metadata_json
                    )
                    VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                    """,
                    runId,
                    traceId,
                    step.getIndex() == null ? i + 1 : step.getIndex(),
                    safe(step.getPhase(), 64),
                    safe(step.getTool(), 128),
                    safe(step.getStatus(), 64),
                    safe(step.getThought(), 1000),
                    safe(step.getAction(), TEXT_LIMIT),
                    safe(step.getObservation(), TEXT_LIMIT),
                    step.getElapsedMs(),
                    toJson(step.getSourceDocIds(), JSON_LIMIT, "[]"),
                    toJson(step.getMetadata(), JSON_LIMIT)
            );
        }
    }

    private List<AgentTraceStepPersistVO> loadSteps(Long runId) {
        return jdbcTemplate.query("""
                SELECT id,run_id,trace_id,step_index,phase,tool,status,thought,action,observation,elapsed_ms,
                       source_doc_ids_json::text,metadata_json::text,created_time
                FROM ai_agent_trace_step
                WHERE run_id = ?
                ORDER BY step_index ASC
                """, (rs, rowNum) -> {
            AgentTraceStepPersistVO vo = new AgentTraceStepPersistVO();
            vo.setId(rs.getLong("id"));
            vo.setRunId(rs.getLong("run_id"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setStepIndex(rs.getInt("step_index"));
            vo.setPhase(rs.getString("phase"));
            vo.setTool(rs.getString("tool"));
            vo.setStatus(rs.getString("status"));
            vo.setThought(rs.getString("thought"));
            vo.setAction(rs.getString("action"));
            vo.setObservation(rs.getString("observation"));
            vo.setElapsedMs(rs.getLong("elapsed_ms"));
            vo.setSourceDocIdsJson(rs.getString("source_doc_ids_json"));
            vo.setMetadataJson(rs.getString("metadata_json"));
            vo.setCreatedTime(toLocalDateTime(rs.getTimestamp("created_time")));
            return vo;
        }, runId);
    }

    private String joinTools(List<ToolQueryResultVO> observations) {
        if (observations == null || observations.isEmpty()) {
            return "";
        }
        Set<String> tools = new LinkedHashSet<>();
        for (ToolQueryResultVO one : observations) {
            if (one != null && StringUtils.hasText(one.getTool())) {
                tools.add(one.getTool());
            }
        }
        return String.join(",", tools);
    }

    private String toJson(Object value, int limit) {
        return toJson(value, limit, "{}");
    }

    private String toJson(Object value, int limit, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return safe(objectMapper.writeValueAsString(value), limit);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }

    private String safe(String text, int limit) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private Long generatedId(KeyHolder keyHolder) {
        if (keyHolder == null || keyHolder.getKeys() == null || keyHolder.getKeys().isEmpty()) {
            Number key = keyHolder == null ? null : keyHolder.getKey();
            return key == null ? null : key.longValue();
        }
        Object id = keyHolder.getKeys().get("id");
        return id instanceof Number n ? n.longValue() : null;
    }
}
