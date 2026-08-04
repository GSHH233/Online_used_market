package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventCreateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventUpdateDTO;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.vo.AiopsActionVO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsMttrMetricsVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AiopsEventServiceImpl implements AiopsEventService {

    private static final List<String> GENERIC_UPDATE_STATUSES = List.of("OPEN", "DIAGNOSED", "PENDING_APPROVAL");
    private static final List<String> APPROVABLE_STATUSES = List.of("OPEN", "DIAGNOSED", "PENDING_APPROVAL");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AiopsEventServiceImpl(@Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate,
                                 ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiopsEventVO createOrUpdate(AiopsEventCreateDTO dto) {
        if (dto == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
        String eventKey = StringUtils.hasText(dto.getEventKey()) ? dto.getEventKey().trim() : "manual-" + UUID.randomUUID();
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO aiops_event(event_key,trace_id,severity,source,title,summary,status,last_seen_at,metadata_json)
                VALUES (?,?,?,?,?,?, 'OPEN', CURRENT_TIMESTAMP, ?::jsonb)
                ON CONFLICT(event_key) DO UPDATE SET
                    trace_id=EXCLUDED.trace_id,
                    severity=EXCLUDED.severity,
                    source=EXCLUDED.source,
                    title=EXCLUDED.title,
                    summary=EXCLUDED.summary,
                    first_seen_at=CASE
                        WHEN aiops_event.status IN ('RESOLVED','ESCALATED','REJECTED') THEN CURRENT_TIMESTAMP
                        ELSE aiops_event.first_seen_at
                    END,
                    status='OPEN',
                    approved_by=NULL,
                    approved_at=NULL,
                    resolved_at=NULL,
                    resolution_type='',
                    manual_required=FALSE,
                    escalation_reason='',
                    last_seen_at=CURRENT_TIMESTAMP,
                    metadata_json=EXCLUDED.metadata_json,
                    updated_time=CURRENT_TIMESTAMP
                RETURNING id
                """, Long.class,
                eventKey,
                safe(dto.getTraceId(), 128),
                normalizeSeverity(dto.getSeverity()),
                safe(dto.getSource(), 64),
                safe(dto.getTitle(), 255),
                safe(dto.getSummary(), 4000),
                writeJson(dto.getMetadata()));
        if (id != null && StringUtils.hasText(dto.getSuggestion())) {
            insertLifecycleAction(id, "SUGGESTION", "SUGGESTED", dto.getSuggestion(), Map.of());
        }
        return get(id);
    }

    @Override
    public AiopsEventVO update(Long eventId, AiopsEventUpdateDTO dto) {
        requireEventId(eventId);
        if (dto == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
        get(eventId);
        String status = normalizeStatus(dto.getStatus());
        if (StringUtils.hasText(status) && !GENERIC_UPDATE_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "状态 " + status + " 必须通过审批、执行或验证接口变更");
        }
        jdbcTemplate.update("""
                UPDATE aiops_event
                SET status=COALESCE(NULLIF(?, ''), status),
                    summary=COALESCE(NULLIF(?, ''), summary),
                    metadata_json=CASE WHEN ? = '' THEN metadata_json ELSE ?::jsonb END,
                    last_seen_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                status,
                safe(dto.getSummary(), 4000),
                dto.getMetadata() == null ? "" : "x",
                dto.getMetadata() == null ? "{}" : writeJson(dto.getMetadata()),
                eventId);
        if (StringUtils.hasText(dto.getSuggestion())) {
            insertLifecycleAction(eventId, "SUGGESTION",
                    Boolean.TRUE.equals(dto.getExecuted()) ? "EXECUTED" : "SUGGESTED",
                    dto.getSuggestion(), Map.of());
        }
        return get(eventId);
    }

    @Override
    public AiopsEventVO approve(Long eventId, Long operatorId, String reason) {
        requireEventId(eventId);
        if (operatorId == null || operatorId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "审批用户不合法");
        }
        AiopsEventVO current = get(eventId);
        if (!APPROVABLE_STATUSES.contains(current.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "当前事件状态不可审批：" + current.getStatus());
        }
        jdbcTemplate.update("""
                UPDATE aiops_event
                SET status='APPROVED', approved_by=?, approved_at=CURRENT_TIMESTAMP,
                    manual_required=FALSE, escalation_reason='', updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, operatorId, eventId);
        insertLifecycleAction(eventId, "APPROVAL", "APPROVED", safe(reason, 4000),
                Map.of("operatorId", operatorId));
        return get(eventId);
    }

    @Override
    public AiopsEventVO reject(Long eventId, Long operatorId, String reason) {
        requireEventId(eventId);
        if (operatorId == null || operatorId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "审批用户不合法");
        }
        AiopsEventVO current = get(eventId);
        if (!APPROVABLE_STATUSES.contains(current.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "当前事件状态不可拒绝：" + current.getStatus());
        }
        jdbcTemplate.update("""
                UPDATE aiops_event
                SET status='REJECTED', approved_by=?, approved_at=CURRENT_TIMESTAMP,
                    manual_required=FALSE, escalation_reason=?, updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, operatorId, safe(reason, 4000), eventId);
        insertLifecycleAction(eventId, "APPROVAL", "REJECTED", safe(reason, 4000),
                Map.of("operatorId", operatorId));
        return get(eventId);
    }

    @Override
    public AiopsEventVO markExecuting(Long eventId, String traceId) {
        requireEventId(eventId);
        int updated = jdbcTemplate.update("""
                UPDATE aiops_event
                SET status='EXECUTING', trace_id=?, manual_required=FALSE,
                    escalation_reason='', updated_time=CURRENT_TIMESTAMP
                WHERE id=? AND status='APPROVED'
                """, safe(traceId, 128), eventId);
        if (updated == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "事件未审批或已进入其他处置阶段");
        }
        return get(eventId);
    }

    @Override
    public AiopsEventVO markVerifying(Long eventId) {
        requireEventId(eventId);
        int updated = jdbcTemplate.update("""
                UPDATE aiops_event SET status='VERIFYING', updated_time=CURRENT_TIMESTAMP
                WHERE id=? AND status='EXECUTING'
                """, eventId);
        if (updated == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "事件不在可验证状态");
        }
        return get(eventId);
    }

    @Override
    public AiopsEventVO markResolved(Long eventId, String resolutionType, String summary) {
        requireEventId(eventId);
        String type = "MANUAL_OVERRIDE".equalsIgnoreCase(resolutionType) ? "MANUAL_OVERRIDE" : "AUTO_VERIFIED";
        int updated = jdbcTemplate.update("""
                UPDATE aiops_event
                SET status='RESOLVED', resolved_at=CURRENT_TIMESTAMP, resolution_type=?,
                    manual_required=FALSE, escalation_reason='',
                    summary=COALESCE(NULLIF(?, ''), summary), updated_time=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('EXECUTING','VERIFYING','ESCALATED','APPROVED')
                """, type, safe(summary, 4000), eventId);
        if (updated == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "事件不在可解决状态");
        }
        return get(eventId);
    }

    @Override
    public AiopsEventVO markEscalated(Long eventId, String reason, String summary) {
        requireEventId(eventId);
        String escalationReason = StringUtils.hasText(reason) ? safe(reason, 4000) : "自动修复或验证失败，需要人工处理";
        jdbcTemplate.update("""
                UPDATE aiops_event
                SET status='ESCALATED', manual_required=TRUE, escalation_reason=?,
                    summary=COALESCE(NULLIF(?, ''), summary), updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, escalationReason, safe(summary, 4000), eventId);
        return get(eventId);
    }

    @Override
    public AiopsEventVO get(Long eventId) {
        requireEventId(eventId);
        List<AiopsEventVO> rows = jdbcTemplate.query(eventSelect() + " WHERE id=?", this::mapEvent, eventId);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "AIOps事件不存在");
        }
        AiopsEventVO vo = rows.get(0);
        vo.setActions(listActions(eventId));
        return vo;
    }

    @Override
    public List<AiopsEventVO> list(String status, String severity, Integer limit) {
        StringBuilder sql = new StringBuilder(eventSelect()).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) {
            sql.append(" AND status = ?");
            args.add(normalizeStatus(status));
        }
        if (StringUtils.hasText(severity)) {
            sql.append(" AND severity = ?");
            args.add(normalizeSeverity(severity));
        }
        sql.append(" ORDER BY last_seen_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit == null ? 20 : limit, 100)));
        return jdbcTemplate.query(sql.toString(), this::mapEvent, args.toArray());
    }

    @Override
    public AiopsMttrMetricsVO mttrMetrics(LocalDateTime from, LocalDateTime to, String source) {
        LocalDateTime end = to == null ? LocalDateTime.now() : to;
        LocalDateTime start = from == null ? end.minusDays(30) : from;
        if (!start.isBefore(end)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "from必须早于to");
        }
        String sourceFilter = StringUtils.hasText(source) ? " AND source=?" : "";
        List<Object> args = new ArrayList<>(List.of(Timestamp.valueOf(start), Timestamp.valueOf(end)));
        if (StringUtils.hasText(source)) {
            args.add(safe(source, 64));
        }
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT
                  COUNT(*) FILTER (WHERE status='RESOLVED' AND resolved_at IS NOT NULL) AS resolved_count,
                  COUNT(*) FILTER (WHERE resolution_type='AUTO_VERIFIED') AS auto_resolved_count,
                  COUNT(*) FILTER (WHERE status='ESCALATED') AS escalated_count,
                  AVG(EXTRACT(EPOCH FROM (resolved_at-first_seen_at))*1000)
                    FILTER (WHERE status='RESOLVED' AND resolved_at IS NOT NULL) AS average_mttr_ms,
                  percentile_cont(0.50) WITHIN GROUP (
                    ORDER BY EXTRACT(EPOCH FROM (resolved_at-first_seen_at))*1000
                  ) FILTER (WHERE status='RESOLVED' AND resolved_at IS NOT NULL) AS p50_mttr_ms,
                  percentile_cont(0.95) WITHIN GROUP (
                    ORDER BY EXTRACT(EPOCH FROM (resolved_at-first_seen_at))*1000
                  ) FILTER (WHERE status='RESOLVED' AND resolved_at IS NOT NULL) AS p95_mttr_ms
                FROM aiops_event
                WHERE first_seen_at>=? AND first_seen_at<?
                """ + sourceFilter, args.toArray());

        List<Object> executedArgs = new ArrayList<>(List.of(Timestamp.valueOf(start), Timestamp.valueOf(end)));
        if (StringUtils.hasText(source)) {
            executedArgs.add(safe(source, 64));
        }
        Long executedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT e.id)
                FROM aiops_event e
                JOIN aiops_action a ON a.event_id=e.id
                WHERE e.first_seen_at>=? AND e.first_seen_at<?
                  AND a.executed=TRUE AND a.action_type NOT IN ('SUGGESTION','APPROVAL')
                """ + (StringUtils.hasText(source) ? " AND e.source=?" : ""), Long.class, executedArgs.toArray());

        long resolved = longValue(row.get("resolved_count"));
        long autoResolved = longValue(row.get("auto_resolved_count"));
        long escalated = longValue(row.get("escalated_count"));
        long executed = executedCount == null ? 0L : executedCount;

        AiopsMttrMetricsVO vo = new AiopsMttrMetricsVO();
        vo.setFrom(start);
        vo.setTo(end);
        vo.setSource(StringUtils.hasText(source) ? source.trim() : "");
        vo.setResolvedCount(resolved);
        vo.setExecutedEventCount(executed);
        vo.setAutoResolvedCount(autoResolved);
        vo.setEscalatedCount(escalated);
        vo.setAverageMttrMs(doubleValue(row.get("average_mttr_ms")));
        vo.setP50MttrMs(doubleValue(row.get("p50_mttr_ms")));
        vo.setP95MttrMs(doubleValue(row.get("p95_mttr_ms")));
        vo.setAutoRepairSuccessRate(executed == 0 ? null : autoResolved / (double) executed);
        vo.setEscalationRate(executed == 0 ? null : escalated / (double) executed);
        return vo;
    }

    private String eventSelect() {
        return """
                SELECT id,event_key,trace_id,severity,source,title,summary,status,metadata_json::text,
                       first_seen_at,last_seen_at,approved_by,approved_at,resolved_at,resolution_type,
                       manual_required,escalation_reason
                FROM aiops_event
                """;
    }

    private AiopsEventVO mapEvent(ResultSet rs, int rowNum) throws SQLException {
        AiopsEventVO vo = new AiopsEventVO();
        vo.setId(rs.getLong("id"));
        vo.setEventKey(rs.getString("event_key"));
        vo.setTraceId(rs.getString("trace_id"));
        vo.setSeverity(rs.getString("severity"));
        vo.setSource(rs.getString("source"));
        vo.setTitle(rs.getString("title"));
        vo.setSummary(rs.getString("summary"));
        vo.setStatus(rs.getString("status"));
        vo.setMetadataJson(rs.getString("metadata_json"));
        vo.setFirstSeenAt(toLocalDateTime(rs.getTimestamp("first_seen_at")));
        vo.setLastSeenAt(toLocalDateTime(rs.getTimestamp("last_seen_at")));
        long approvedBy = rs.getLong("approved_by");
        vo.setApprovedBy(rs.wasNull() ? null : approvedBy);
        vo.setApprovedAt(toLocalDateTime(rs.getTimestamp("approved_at")));
        vo.setResolvedAt(toLocalDateTime(rs.getTimestamp("resolved_at")));
        vo.setResolutionType(rs.getString("resolution_type"));
        vo.setManualRequired(rs.getBoolean("manual_required"));
        vo.setEscalationReason(rs.getString("escalation_reason"));
        return vo;
    }

    private List<AiopsActionVO> listActions(Long eventId) {
        return jdbcTemplate.query("""
                SELECT id,event_id,action_type,trace_id,idempotency_key,status,verification_status,
                       suggestion,executed,metadata_json::text,created_time,started_at,finished_at
                FROM aiops_action
                WHERE event_id=?
                ORDER BY id ASC
                """, (rs, rowNum) -> {
            AiopsActionVO vo = new AiopsActionVO();
            vo.setId(rs.getLong("id"));
            vo.setEventId(rs.getLong("event_id"));
            vo.setActionType(rs.getString("action_type"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setIdempotencyKey(rs.getString("idempotency_key"));
            vo.setStatus(rs.getString("status"));
            vo.setVerificationStatus(rs.getString("verification_status"));
            vo.setSuggestion(rs.getString("suggestion"));
            vo.setExecuted(rs.getBoolean("executed"));
            vo.setMetadataJson(rs.getString("metadata_json"));
            vo.setCreatedTime(toLocalDateTime(rs.getTimestamp("created_time")));
            vo.setStartedAt(toLocalDateTime(rs.getTimestamp("started_at")));
            vo.setFinishedAt(toLocalDateTime(rs.getTimestamp("finished_at")));
            return vo;
        }, eventId);
    }

    private void insertLifecycleAction(Long eventId, String type, String status, String suggestion,
                                       Map<String, Object> metadata) {
        jdbcTemplate.update("""
                INSERT INTO aiops_action(event_id,action_type,status,suggestion,executed,metadata_json)
                VALUES (?, ?, ?, ?, FALSE, ?::jsonb)
                """, eventId, type, status, safe(suggestion, 4000), writeJson(metadata));
    }

    private void requireEventId(Long eventId) {
        if (eventId == null || eventId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "eventId不合法");
        }
    }

    private String normalizeSeverity(String value) {
        if (!StringUtils.hasText(value)) {
            return "INFO";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (List.of("CRITICAL", "WARNING", "WARN", "INFO").contains(normalized)) {
            return "WARN".equals(normalized) ? "WARNING" : normalized;
        }
        return "INFO";
    }

    private String normalizeStatus(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String safe(String value, int limit) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
