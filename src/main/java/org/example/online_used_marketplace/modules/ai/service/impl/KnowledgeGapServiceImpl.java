package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeGapLinkDocDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeGapUpdateDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeGapUnresolvedFeedbackDTO;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStatus;
import org.example.online_used_marketplace.modules.ai.event.RagChatOutcomeEvent;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeDocService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeGapService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeIndexService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.RagChatService;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeGapMetricsVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeGapRegressionVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeGapVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeIndexTaskVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.WsChatDoneVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class KnowledgeGapServiceImpl implements KnowledgeGapService {

    private static final Set<String> STATUSES = Set.of(
            "OPEN", "TRIAGED", "DOC_LINKED", "INDEXING", "REGRESSING",
            "RESOLVED", "REGRESSION_FAILED", "DISMISSED");
    private static final Set<String> USER_UPDATE_STATUSES = Set.of("OPEN", "TRIAGED", "DISMISSED");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern TOKEN = Pattern.compile("(?i)(bearer\\s+)?(?:sk-|eyJ)[A-Za-z0-9._-]{16,}");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocService knowledgeDocService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final RagChatService ragChatService;

    public KnowledgeGapServiceImpl(
            @Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            KnowledgeDocService knowledgeDocService,
            KnowledgeIndexService knowledgeIndexService,
            KnowledgeSearchService knowledgeSearchService,
            RagChatService ragChatService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.knowledgeDocService = knowledgeDocService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.knowledgeSearchService = knowledgeSearchService;
        this.ragChatService = ragChatService;
    }

    @EventListener
    public void onRagChatOutcome(RagChatOutcomeEvent event) {
        if (event == null || (event.sessionId() != null && event.sessionId().startsWith("gap-regression-"))) {
            return;
        }
        try {
            saveTurnAudit(event);
            if (StringUtils.hasText(event.reasonCode())) {
                captureGap(event);
            }
        } catch (Exception ex) {
            log.warn("[KnowledgeGap] outcome persistence skipped, traceId={}, reason={}", event.traceId(), ex.getMessage());
        }
    }

    @Override
    public KnowledgeGapVO captureUserUnresolved(KnowledgeGapUnresolvedFeedbackDTO request) {
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "query不能为空");
        }
        Long id = captureGap(new RagChatOutcomeEvent(
                request.getQuery(), request.getSessionId(), request.getTraceId(), 0,
                "", "userFeedback=true", false, "", List.of(), "USER_UNRESOLVED"));
        return get(id);
    }

    @Override
    public List<KnowledgeGapVO> list(String status, String priority, Integer limit) {
        StringBuilder sql = new StringBuilder(gapSelect()).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) {
            sql.append(" AND status=?");
            args.add(validateStatus(status, STATUSES));
        }
        if (StringUtils.hasText(priority)) {
            sql.append(" AND priority=?");
            args.add(validatePriority(priority));
        }
        sql.append(" ORDER BY last_seen_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit == null ? 20 : limit, 100)));
        return jdbcTemplate.query(sql.toString(), this::mapGap, args.toArray());
    }

    @Override
    public KnowledgeGapVO get(Long gapId) {
        requireId(gapId);
        List<KnowledgeGapVO> rows = jdbcTemplate.query(gapSelect() + " WHERE id=?", this::mapGap, gapId);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识缺口不存在");
        }
        return rows.get(0);
    }

    @Override
    public KnowledgeGapVO update(Long gapId, KnowledgeGapUpdateDTO request) {
        KnowledgeGapVO current = get(gapId);
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
        String status = StringUtils.hasText(request.getStatus())
                ? validateStatus(request.getStatus(), USER_UPDATE_STATUSES) : current.getStatus();
        String priority = StringUtils.hasText(request.getPriority())
                ? validatePriority(request.getPriority()) : current.getPriority();
        jdbcTemplate.update("""
                UPDATE ai_knowledge_gap
                SET status=?,priority=?,resolved_at=CASE WHEN ?='DISMISSED' THEN CURRENT_TIMESTAMP ELSE resolved_at END,
                    updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, status, priority, status, gapId);
        return get(gapId);
    }

    @Override
    public KnowledgeGapVO linkDoc(Long gapId, KnowledgeGapLinkDocDTO request) {
        KnowledgeGapVO gap = get(gapId);
        if (request == null || request.getDocId() == null || request.getDocId() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "docId不合法");
        }
        if ("MISSING_FIELDS".equals(gap.getReasonCode())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "缺少用户字段不应通过新增知识文档解决");
        }
        knowledgeDocService.getByIdOrThrow(request.getDocId());
        Long taskId = request.getTaskId();
        if (taskId != null) {
            KnowledgeIndexTaskVO task = knowledgeIndexService.getTask(taskId);
            if (!request.getDocId().equals(task.getDocId())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "taskId与docId不匹配");
            }
        }
        jdbcTemplate.update("""
                UPDATE ai_knowledge_gap
                SET linked_doc_id=?,linked_task_id=?,status='DOC_LINKED',resolved_at=NULL,updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, request.getDocId(), taskId, gapId);
        return get(gapId);
    }

    @Override
    public KnowledgeGapRegressionVO regress(Long gapId) {
        KnowledgeGapVO gap = get(gapId);
        if ("MISSING_FIELDS".equals(gap.getReasonCode())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "MISSING_FIELDS缺口不能触发知识回归");
        }
        if (gap.getLinkedDocId() == null) {
            throw new BusinessException(ResultCode.CONFLICT, "请先关联知识文档");
        }
        KnowledgeIndexTaskVO task = resolveVerifiedTask(gap);
        long started = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString();
        jdbcTemplate.update("UPDATE ai_knowledge_gap SET status='REGRESSING',linked_task_id=?,updated_time=CURRENT_TIMESTAMP WHERE id=?",
                task.getId(), gapId);
        boolean retrievalHit = false;
        boolean sourceHit = false;
        boolean rejectResolved = false;
        String error = "";
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            KnowledgeSearchResponseVO retrieval = knowledgeSearchService.searchHybrid(gap.getQuery(), 5, null);
            retrievalHit = retrieval != null && retrieval.getHits() != null && retrieval.getHits().stream()
                    .anyMatch(hit -> gap.getLinkedDocId().equals(hit.getDocId()));
            WsChatDoneVO answer = ragChatService.chatForEvaluation("gap-regression-" + gapId, gap.getQuery(), traceId, 5, null);
            sourceHit = answer != null && answer.getSources() != null && answer.getSources().stream()
                    .anyMatch(source -> gap.getLinkedDocId().equals(source.getDocId()));
            rejectResolved = answer != null && !Boolean.TRUE.equals(answer.getLowConfidence())
                    && !containsRefusal(answer.getAnswer());
            details.put("retrievalDebug", retrieval == null ? "" : retrieval.getDebugInfo());
            details.put("answer", answer == null ? "" : answer.getAnswer());
            details.put("sourceCount", answer == null || answer.getSources() == null ? 0 : answer.getSources().size());
        } catch (Exception ex) {
            error = safe(ex.getMessage(), 2000);
            details.put("exception", ex.getClass().getSimpleName());
        }
        boolean passed = retrievalHit && sourceHit && rejectResolved && !StringUtils.hasText(error);
        long latency = System.currentTimeMillis() - started;
        Long evalRunId = persistEval(gap, passed, latency, retrievalHit, sourceHit, rejectResolved, error, details);
        Long regressionId = jdbcTemplate.queryForObject("""
                INSERT INTO ai_knowledge_gap_regression(
                    gap_id,linked_doc_id,linked_task_id,retrieval_hit,source_hit,reject_resolved,passed,
                    latency_ms,eval_run_id,trace_id,error_msg,details_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb) RETURNING id
                """, Long.class, gapId, gap.getLinkedDocId(), task.getId(), retrievalHit, sourceHit,
                rejectResolved, passed, latency, evalRunId, traceId, error, writeJson(details));
        jdbcTemplate.update("""
                UPDATE ai_knowledge_gap
                SET status=?,resolved_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, passed ? "RESOLVED" : "REGRESSION_FAILED", passed, gapId);
        return getRegression(regressionId);
    }

    @Override
    public List<KnowledgeGapRegressionVO> listRegressions(Long gapId, Integer limit) {
        get(gapId);
        return jdbcTemplate.query(regressionSelect() + " WHERE gap_id=? ORDER BY created_time DESC LIMIT ?",
                this::mapRegression, gapId, Math.max(1, Math.min(limit == null ? 20 : limit, 100)));
    }

    @Override
    public KnowledgeGapMetricsVO metrics(LocalDateTime from, LocalDateTime to) {
        LocalDateTime end = to == null ? LocalDateTime.now() : to;
        LocalDateTime start = from == null ? end.minusDays(30) : from;
        if (!start.isBefore(end)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "from必须早于to");
        }
        Map<String, Object> gap = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) total_gaps,
                       COUNT(*) FILTER (WHERE status='RESOLVED') resolved_gaps,
                       COUNT(*) FILTER (WHERE status IN ('RESOLVED','DISMISSED')) completed_gaps,
                       COUNT(*) FILTER (WHERE recurrence_count>0) recurrent_gaps,
                       COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED','DISMISSED')) open_gaps,
                       AVG(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-first_seen_at))*1000)
                         FILTER (WHERE status NOT IN ('RESOLVED','DISMISSED')) average_open_age_ms
                FROM ai_knowledge_gap WHERE first_seen_at>=? AND first_seen_at<?
                """, Timestamp.valueOf(start), Timestamp.valueOf(end));
        Map<String, Object> chat = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) rag_turns,COUNT(*) FILTER (WHERE refused=TRUE) refused_turns
                FROM ai_rag_chat_turn_audit WHERE created_time>=? AND created_time<?
                """, Timestamp.valueOf(start), Timestamp.valueOf(end));
        long total = number(gap, "total_gaps");
        long resolved = number(gap, "resolved_gaps");
        long completed = number(gap, "completed_gaps");
        long recurrent = number(gap, "recurrent_gaps");
        long open = number(gap, "open_gaps");
        long turns = number(chat, "rag_turns");
        long refused = number(chat, "refused_turns");
        KnowledgeGapMetricsVO vo = new KnowledgeGapMetricsVO();
        vo.setFrom(start);
        vo.setTo(end);
        vo.setTotalGaps(total);
        vo.setResolvedGaps(resolved);
        vo.setCompletedGovernanceGaps(completed);
        vo.setRecurrentGaps(recurrent);
        vo.setOpenGaps(open);
        vo.setRagChatTurns(turns);
        vo.setRefusedTurns(refused);
        vo.setGapResolutionRate(rate(resolved, completed));
        vo.setRecurrenceRate(rate(recurrent, resolved));
        vo.setAverageOpenGapAgeMs(decimal(gap.get("average_open_age_ms")));
        vo.setRefusalRate(rate(refused, turns));
        return vo;
    }

    private void saveTurnAudit(RagChatOutcomeEvent event) {
        String traceId = StringUtils.hasText(event.traceId()) ? event.traceId() : UUID.randomUUID().toString();
        boolean refused = StringUtils.hasText(event.reasonCode());
        jdbcTemplate.update("""
                INSERT INTO ai_rag_chat_turn_audit(
                    trace_id,session_id,query,refused,reason_code,low_confidence,source_count,retrieval_mode)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(trace_id) DO NOTHING
                """, traceId, safe(event.sessionId(), 128), redact(event.query()), refused,
                safe(event.reasonCode(), 32), Boolean.TRUE.equals(event.lowConfidence()),
                event.sources() == null ? 0 : event.sources().size(), safe(event.retrievalMode(), 32));
    }

    private Long captureGap(RagChatOutcomeEvent event) {
        String query = redact(event.query());
        String normalized = normalizeQuery(query);
        String reason = safe(event.reasonCode(), 32).toUpperCase(Locale.ROOT);
        String fingerprint = sha256(normalized);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("topK", event.topK());
        metadata.put("retrievalMode", event.retrievalMode());
        metadata.put("retrievalDebug", safe(event.retrievalDebug(), 2000));
        metadata.put("sourceCount", event.sources() == null ? 0 : event.sources().size());
        return jdbcTemplate.queryForObject("""
                INSERT INTO ai_knowledge_gap(
                    fingerprint,query,normalized_query,reason_code,status,priority,latest_trace_id,
                    latest_session_id,metadata_json)
                VALUES (?,?,?,?,'OPEN','MEDIUM',?,?,?::jsonb)
                ON CONFLICT(fingerprint,reason_code) DO UPDATE SET
                    query=EXCLUDED.query,
                    occurrence_count=ai_knowledge_gap.occurrence_count+1,
                    recurrence_count=ai_knowledge_gap.recurrence_count+
                        CASE WHEN ai_knowledge_gap.status='RESOLVED' THEN 1 ELSE 0 END,
                    status=CASE WHEN ai_knowledge_gap.status='RESOLVED' THEN 'OPEN' ELSE ai_knowledge_gap.status END,
                    resolved_at=CASE WHEN ai_knowledge_gap.status='RESOLVED' THEN NULL ELSE ai_knowledge_gap.resolved_at END,
                    latest_trace_id=EXCLUDED.latest_trace_id,
                    latest_session_id=EXCLUDED.latest_session_id,
                    metadata_json=EXCLUDED.metadata_json,
                    last_seen_at=CURRENT_TIMESTAMP,
                    updated_time=CURRENT_TIMESTAMP
                RETURNING id
                """, Long.class, fingerprint, query, normalized, reason, safe(event.traceId(), 128),
                safe(event.sessionId(), 128), writeJson(metadata));
    }

    private KnowledgeIndexTaskVO resolveVerifiedTask(KnowledgeGapVO gap) {
        KnowledgeIndexTaskVO task = gap.getLinkedTaskId() == null ? null : knowledgeIndexService.getTask(gap.getLinkedTaskId());
        if (task == null) {
            task = knowledgeIndexService.listTasks(gap.getLinkedDocId(), KnowledgeIndexTaskStatus.SUCCESS, null, 20)
                    .stream().filter(one -> Boolean.TRUE.equals(one.getConsistent())).findFirst().orElse(null);
        }
        if (task == null || !KnowledgeIndexTaskStatus.SUCCESS.equals(task.getStatus())
                || !Boolean.TRUE.equals(task.getConsistent()) || !gap.getLinkedDocId().equals(task.getDocId())) {
            throw new BusinessException(ResultCode.CONFLICT, "关联文档尚未完成三存储一致入库");
        }
        return task;
    }

    private Long persistEval(KnowledgeGapVO gap, boolean passed, long latency, boolean retrievalHit,
                             boolean sourceHit, boolean rejectResolved, String error, Map<String, Object> details) {
        String report = "knowledge-gap-regression-" + gap.getId() + "-" + System.currentTimeMillis();
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO ai_eval_run(eval_type,report_file,status,total_cases,passed_cases,failed_cases,
                                        success_rate,p50_ms,p95_ms,metrics_json,preview,report_mtime)
                VALUES ('knowledge_gap_regression',?,?,?,?,?,?,?,?,?::jsonb,?,CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, report, passed ? "OK" : "RISK", 1, passed ? 1 : 0, passed ? 0 : 1,
                passed ? 1D : 0D, (double) latency, (double) latency,
                writeJson(Map.of("retrievalHit", retrievalHit, "sourceHit", sourceHit,
                        "rejectResolved", rejectResolved)), safe(error, 500));
        jdbcTemplate.update("""
                INSERT INTO ai_eval_case_result(eval_run_id,case_id,status,latency_ms,details_json)
                VALUES (?,?,?,?,?::jsonb)
                """, runId, "GAP-" + gap.getId(), passed ? "PASS" : "FAIL", (double) latency, writeJson(details));
        return runId;
    }

    private KnowledgeGapRegressionVO getRegression(Long id) {
        return jdbcTemplate.queryForObject(regressionSelect() + " WHERE id=?", this::mapRegression, id);
    }

    private String gapSelect() {
        return """
                SELECT id,fingerprint,query,normalized_query,reason_code,status,priority,occurrence_count,
                       recurrence_count,linked_doc_id,linked_task_id,latest_trace_id,latest_session_id,
                       metadata_json::text,first_seen_at,last_seen_at,resolved_at
                FROM ai_knowledge_gap
                """;
    }

    private KnowledgeGapVO mapGap(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeGapVO vo = new KnowledgeGapVO();
        vo.setId(rs.getLong("id"));
        vo.setFingerprint(rs.getString("fingerprint"));
        vo.setQuery(rs.getString("query"));
        vo.setNormalizedQuery(rs.getString("normalized_query"));
        vo.setReasonCode(rs.getString("reason_code"));
        vo.setStatus(rs.getString("status"));
        vo.setPriority(rs.getString("priority"));
        vo.setOccurrenceCount(rs.getInt("occurrence_count"));
        vo.setRecurrenceCount(rs.getInt("recurrence_count"));
        vo.setLinkedDocId(nullableLong(rs, "linked_doc_id"));
        vo.setLinkedTaskId(nullableLong(rs, "linked_task_id"));
        vo.setLatestTraceId(rs.getString("latest_trace_id"));
        vo.setLatestSessionId(rs.getString("latest_session_id"));
        vo.setMetadataJson(rs.getString("metadata_json"));
        vo.setFirstSeenAt(toTime(rs.getTimestamp("first_seen_at")));
        vo.setLastSeenAt(toTime(rs.getTimestamp("last_seen_at")));
        vo.setResolvedAt(toTime(rs.getTimestamp("resolved_at")));
        return vo;
    }

    private String regressionSelect() {
        return """
                SELECT id,gap_id,linked_doc_id,linked_task_id,retrieval_hit,source_hit,reject_resolved,
                       passed,latency_ms,eval_run_id,trace_id,error_msg,details_json::text,created_time
                FROM ai_knowledge_gap_regression
                """;
    }

    private KnowledgeGapRegressionVO mapRegression(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeGapRegressionVO vo = new KnowledgeGapRegressionVO();
        vo.setId(rs.getLong("id"));
        vo.setGapId(rs.getLong("gap_id"));
        vo.setLinkedDocId(nullableLong(rs, "linked_doc_id"));
        vo.setLinkedTaskId(nullableLong(rs, "linked_task_id"));
        vo.setRetrievalHit(rs.getBoolean("retrieval_hit"));
        vo.setSourceHit(rs.getBoolean("source_hit"));
        vo.setRejectResolved(rs.getBoolean("reject_resolved"));
        vo.setPassed(rs.getBoolean("passed"));
        vo.setLatencyMs(nullableLong(rs, "latency_ms"));
        vo.setEvalRunId(nullableLong(rs, "eval_run_id"));
        vo.setTraceId(rs.getString("trace_id"));
        vo.setErrorMsg(rs.getString("error_msg"));
        vo.setDetailsJson(rs.getString("details_json"));
        vo.setCreatedTime(toTime(rs.getTimestamp("created_time")));
        return vo;
    }

    private boolean containsRefusal(String answer) {
        if (!StringUtils.hasText(answer)) {
            return true;
        }
        return answer.contains("证据不足") || answer.contains("无法确定") || answer.contains("不能确定");
    }

    private String redact(String text) {
        String value = text == null ? "" : text;
        value = TOKEN.matcher(value).replaceAll("[REDACTED_TOKEN]");
        value = EMAIL.matcher(value).replaceAll("[REDACTED_EMAIL]");
        return PHONE.matcher(value).replaceAll("[REDACTED_PHONE]");
    }

    private String normalizeQuery(String query) {
        return query.toLowerCase(Locale.ROOT)
                .replaceAll("请问|麻烦|帮我|帮忙|一下|谢谢", "")
                .replaceAll("[\\p{P}\\p{S}\\s]+", "")
                .replaceAll("(吗|呢|呀|啊)$", "")
                .trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "知识缺口指纹计算失败");
        }
    }

    private String validateStatus(String value, Set<String> allowed) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的知识缺口状态：" + normalized);
        }
        return normalized;
    }

    private String validatePriority(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的优先级：" + normalized);
        }
        return normalized;
    }

    private void requireId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "gapId不合法");
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime toTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String safe(String value, int limit) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : numerator / (double) denominator;
    }
}
