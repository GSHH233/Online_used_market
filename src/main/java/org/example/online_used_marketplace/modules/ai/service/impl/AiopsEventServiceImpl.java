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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AiopsEventServiceImpl implements AiopsEventService {

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
                    status='OPEN',
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
            jdbcTemplate.update("""
                    INSERT INTO aiops_action(event_id,action_type,status,suggestion,executed,metadata_json)
                    VALUES (?, 'SUGGESTION', 'SUGGESTED', ?, FALSE, '{}'::jsonb)
                    """, id, dto.getSuggestion());
        }
        return get(id);
    }

    @Override
    public AiopsEventVO update(Long eventId, AiopsEventUpdateDTO dto) {
        if (eventId == null || eventId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "eventId不合法");
        }
        if (dto == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
        AiopsEventVO current = get(eventId);
        jdbcTemplate.update("""
                UPDATE aiops_event
                SET status=COALESCE(NULLIF(?, ''), status),
                    summary=COALESCE(NULLIF(?, ''), summary),
                    metadata_json=CASE WHEN ? = '' THEN metadata_json ELSE ?::jsonb END,
                    last_seen_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                safe(dto.getStatus(), 32),
                safe(dto.getSummary(), 4000),
                dto.getMetadata() == null ? "" : "x",
                dto.getMetadata() == null ? "{}" : writeJson(dto.getMetadata()),
                eventId);
        if (StringUtils.hasText(dto.getSuggestion())) {
            jdbcTemplate.update("""
                    INSERT INTO aiops_action(event_id,action_type,status,suggestion,executed,metadata_json)
                    VALUES (?, 'SUGGESTION', ?, ?, ?, '{}'::jsonb)
                    """, eventId, Boolean.TRUE.equals(dto.getExecuted()) ? "EXECUTED" : "SUGGESTED", dto.getSuggestion(), Boolean.TRUE.equals(dto.getExecuted()));
        } else if (dto.getExecuted() != null) {
            jdbcTemplate.update("""
                    UPDATE aiops_action
                    SET executed=?, status=CASE WHEN ? THEN 'EXECUTED' ELSE status END
                    WHERE event_id=?
                    """, dto.getExecuted(), dto.getExecuted(), eventId);
        }
        return get(current.getId());
    }

    @Override
    public AiopsEventVO get(Long eventId) {
        if (eventId == null || eventId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "eventId不合法");
        }
        List<AiopsEventVO> rows = jdbcTemplate.query("""
                SELECT id,event_key,trace_id,severity,source,title,summary,status,metadata_json::text,first_seen_at,last_seen_at
                FROM aiops_event
                WHERE id=?
                """, (rs, rowNum) -> {
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
            return vo;
        }, eventId);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "AIOps事件不存在");
        }
        AiopsEventVO vo = rows.get(0);
        vo.setActions(listActions(eventId));
        return vo;
    }

    @Override
    public List<AiopsEventVO> list(String status, String severity, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id,event_key,trace_id,severity,source,title,summary,status,metadata_json::text,first_seen_at,last_seen_at
                FROM aiops_event
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (StringUtils.hasText(severity)) {
            sql.append(" AND severity = ?");
            args.add(normalizeSeverity(severity));
        }
        sql.append(" ORDER BY last_seen_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit == null ? 20 : limit, 100)));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
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
            return vo;
        }, args.toArray());
    }

    private List<AiopsActionVO> listActions(Long eventId) {
        return jdbcTemplate.query("""
                SELECT id,event_id,action_type,status,suggestion,executed,metadata_json::text,created_time
                FROM aiops_action
                WHERE event_id=?
                ORDER BY id ASC
                """, (rs, rowNum) -> {
            AiopsActionVO vo = new AiopsActionVO();
            vo.setId(rs.getLong("id"));
            vo.setEventId(rs.getLong("event_id"));
            vo.setActionType(rs.getString("action_type"));
            vo.setStatus(rs.getString("status"));
            vo.setSuggestion(rs.getString("suggestion"));
            vo.setExecuted(rs.getBoolean("executed"));
            vo.setMetadataJson(rs.getString("metadata_json"));
            vo.setCreatedTime(toLocalDateTime(rs.getTimestamp("created_time")));
            return vo;
        }, eventId);
    }

    private String normalizeSeverity(String value) {
        if (!StringUtils.hasText(value)) {
            return "INFO";
        }
        String normalized = value.trim().toUpperCase();
        if ("CRITICAL".equals(normalized) || "WARNING".equals(normalized) || "INFO".equals(normalized)) {
            return normalized;
        }
        return "INFO";
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
}
