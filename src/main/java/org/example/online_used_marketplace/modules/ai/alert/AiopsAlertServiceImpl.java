package org.example.online_used_marketplace.modules.ai.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.config.AiopsAlertProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Service
public class AiopsAlertServiceImpl implements AiopsAlertService {

    private static final List<String> VALID_DELIVERY_STATUSES = List.of("FIRING", "RESOLVED");
    private static final List<String> VALID_ALERT_STATUSES = List.of("FIRING", "RESOLVED");
    private static final List<String> SOURCE_RECOVERY_STATES = List.of("OPEN", "DIAGNOSED");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiopsAlertProperties properties;
    private final ObjectProvider<AiopsAlertInvestigationPublisher> publisherProvider;
    private final AiopsAlertDeliveryStateStore deliveryStateStore;
    private final TransactionTemplate transactionTemplate;

    public AiopsAlertServiceImpl(
            @Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AiopsAlertProperties properties,
            ObjectProvider<AiopsAlertInvestigationPublisher> publisherProvider,
            AiopsAlertDeliveryStateStore deliveryStateStore,
            @Qualifier("memoryGraphTransactionManager") PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.publisherProvider = publisherProvider;
        this.deliveryStateStore = deliveryStateStore;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public AiopsAlertWebhookVO receive(AlertmanagerWebhookDTO webhook, String presentedToken) {
        validateToken(presentedToken);
        NormalizedWebhook normalized = normalize(webhook);
        String payloadHash = AiopsAlertCanonicalizer.payloadHash(normalized.webhook(), objectMapper);
        AiopsAlertDeliveryVO existing = findDeliveryByPayloadHash(payloadHash);
        if (existing != null) {
            return webhookResult(existing, true);
        }

        String traceId = "aiops-alert-" + payloadHash.substring(0, 16);
        PersistedAlert persisted = transactionTemplate.execute(status -> persist(normalized, payloadHash, traceId));
        if (persisted == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "告警事务未完成");
        }
        if (persisted.duplicate()) {
            return webhookResult(getDelivery(persisted.deliveryId()), true);
        }
        Long deliveryId = persisted.deliveryId();
        List<Long> eventIds = persisted.eventIds();

        if ("FIRING".equals(normalized.status())) {
            AiopsAlertInvestigationPublisher publisher = publisherProvider.getIfAvailable();
            if (publisher == null) {
                deliveryStateStore.markFailed(deliveryId, "告警调查发布器不可用");
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "告警已落库，但 RabbitMQ 调查发布器不可用");
            }
            for (Long eventId : eventIds) {
                String fingerprint = fingerprintFor(eventId);
                boolean published = publisher.publish(new AiopsAlertInvestigationMessage(
                        deliveryId, eventId, fingerprint, normalized.correlationKey(), traceId,
                        payloadHash, LocalDateTime.now()));
                if (!published) {
                    deliveryStateStore.markFailed(deliveryId, "RabbitMQ 调查消息发布失败");
                    throw new BusinessException(ResultCode.INTERNAL_ERROR, "告警已落库，但 RabbitMQ 调查消息发布失败");
                }
            }
            deliveryStateStore.markPublished(deliveryId);
        } else {
            deliveryStateStore.markResolved(deliveryId);
        }

        AiopsAlertDeliveryVO delivery = getDelivery(deliveryId);
        return webhookResult(delivery, false);
    }

    @Override
    public AiopsAlertDeliveryVO getDelivery(Long deliveryId) {
        if (deliveryId == null || deliveryId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "deliveryId不合法");
        }
        List<AiopsAlertDeliveryVO> rows = jdbcTemplate.query("""
                SELECT id,delivery_key,payload_hash,group_key,status,alert_count,processing_status,
                       error_msg,trace_id,event_ids_json::text,payload_json::text,created_time,updated_time
                FROM aiops_alert_delivery
                WHERE id=?
                """, this::mapDelivery, deliveryId);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "告警 delivery 不存在");
        }
        return rows.get(0);
    }

    @Override
    public AiopsChangeEventVO createChangeEvent(AiopsChangeEventCreateDTO request) {
        if (request == null || !StringUtils.hasText(request.getService())
                || !StringUtils.hasText(request.getChangeType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "service和changeType不能为空");
        }
        Map<String, String> metadata = boundedMap(request.getMetadata(), "metadata");
        LocalDateTime occurredAt = request.getOccurredAt() == null ? LocalDateTime.now() : request.getOccurredAt();
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO aiops_change_event(service,environment,change_type,version,trace_id,summary,metadata_json,occurred_at)
                VALUES (?,?,?,?,?,?,?::jsonb,?)
                RETURNING id
                """, Long.class,
                safe(request.getService(), 128), safe(request.getEnvironment(), 64),
                safe(request.getChangeType(), 64), safe(request.getVersion(), 128),
                safe(request.getTraceId(), 128), safe(request.getSummary(), 4000),
                writeJson(metadata), Timestamp.valueOf(occurredAt));
        return getChangeEvent(id);
    }

    AiopsChangeEventVO getChangeEvent(Long id) {
        List<AiopsChangeEventVO> rows = jdbcTemplate.query("""
                SELECT id,service,environment,change_type,version,trace_id,summary,metadata_json::text,
                       occurred_at,created_time
                FROM aiops_change_event WHERE id=?
                """, (rs, rowNum) -> {
            AiopsChangeEventVO vo = new AiopsChangeEventVO();
            vo.setId(rs.getLong("id"));
            vo.setService(rs.getString("service"));
            vo.setEnvironment(rs.getString("environment"));
            vo.setChangeType(rs.getString("change_type"));
            vo.setVersion(rs.getString("version"));
            vo.setTraceId(rs.getString("trace_id"));
            vo.setSummary(rs.getString("summary"));
            vo.setMetadataJson(rs.getString("metadata_json"));
            vo.setOccurredAt(toLocalDateTime(rs.getTimestamp("occurred_at")));
            vo.setCreatedTime(toLocalDateTime(rs.getTimestamp("created_time")));
            return vo;
        }, id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "变更事件不存在");
        }
        return rows.get(0);
    }

    private NormalizedWebhook normalize(AlertmanagerWebhookDTO webhook) {
        if (webhook == null || webhook.getAlerts() == null || webhook.getAlerts().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "alerts不能为空");
        }
        if (webhook.getAlerts().size() > boundedMax(properties.getMaxAlertsPerDelivery(), 100)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "单次告警数量超过限制");
        }
        String deliveryStatus = normalizedStatus(webhook.getStatus(), VALID_DELIVERY_STATUSES, "status");
        AlertmanagerWebhookDTO copy = new AlertmanagerWebhookDTO();
        copy.setReceiver(required(webhook.getReceiver(), 160, "receiver"));
        copy.setStatus(deliveryStatus);
        copy.setGroupKey(safe(webhook.getGroupKey(), 160, "groupKey"));
        copy.setExternalURL(safe(webhook.getExternalURL(), 512, "externalURL"));
        copy.setVersion(safe(webhook.getVersion(), 32, "version"));
        copy.setGroupLabels(boundedMap(webhook.getGroupLabels(), "groupLabels"));
        copy.setCommonLabels(boundedMap(webhook.getCommonLabels(), "commonLabels"));
        copy.setCommonAnnotations(boundedMap(webhook.getCommonAnnotations(), "commonAnnotations"));
        List<AlertmanagerAlertDTO> alerts = new ArrayList<>();
        for (AlertmanagerAlertDTO source : webhook.getAlerts()) {
            if (source == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "alerts不能包含空项");
            }
            AlertmanagerAlertDTO item = new AlertmanagerAlertDTO();
            item.setStatus(normalizedStatus(source.getStatus(), VALID_ALERT_STATUSES, "alert.status"));
            item.setLabels(boundedMap(source.getLabels(), "labels"));
            item.setAnnotations(boundedMap(source.getAnnotations(), "annotations"));
            item.setStartsAt(safe(source.getStartsAt(), 64, "alert.startsAt"));
            item.setEndsAt(safe(source.getEndsAt(), 64, "alert.endsAt"));
            item.setGeneratorURL(safe(source.getGeneratorURL(), 1024, "alert.generatorURL"));
            item.setFingerprint(safe(source.getFingerprint(), 128, "alert.fingerprint"));
            alerts.add(item);
        }
        copy.setAlerts(alerts);
        String correlationKey = StringUtils.hasText(copy.getGroupKey())
                ? copy.getGroupKey()
                : "group-" + AiopsAlertCanonicalizer.sha256(AiopsAlertCanonicalizer.canonicalPairs(copy.getGroupLabels())).substring(0, 32);
        return new NormalizedWebhook(copy, deliveryStatus, safe(correlationKey, 160, "correlationKey"));
    }

    private PersistedAlert persist(NormalizedWebhook normalized, String payloadHash, String traceId) {
        DeliveryInsert deliveryInsert = insertDelivery(normalized, payloadHash, traceId);
        if (deliveryInsert.duplicate()) {
            return new PersistedAlert(deliveryInsert.id(), List.of(), true);
        }
        List<Long> eventIds = new ArrayList<>();
        for (AlertmanagerAlertDTO alert : normalized.webhook().getAlerts()) {
            Long eventId = processAlert(normalized, alert, payloadHash, traceId);
            if (eventId != null) {
                eventIds.add(eventId);
            }
        }
        updateDeliveryEvents(deliveryInsert.id(), eventIds);
        return new PersistedAlert(deliveryInsert.id(), eventIds, false);
    }

    private DeliveryInsert insertDelivery(NormalizedWebhook normalized, String payloadHash, String traceId) {
        String payloadJson = writeJson(AiopsAlertCanonicalizer.canonicalPayload(normalized.webhook()));
        String deliveryKey = "alertmanager:"
                + AiopsAlertCanonicalizer.sha256(normalized.correlationKey() + ":" + payloadHash);
        try {
            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO aiops_alert_delivery(delivery_key,payload_hash,group_key,status,alert_count,
                                                     processing_status,error_msg,trace_id,event_ids_json,payload_json)
                    VALUES (?,?,?,?,?,'RECEIVED','',?,'{}'::jsonb,?::jsonb)
                    ON CONFLICT(payload_hash) DO NOTHING
                    RETURNING id
                    """, Long.class, safe(deliveryKey, 160, "deliveryKey"), payloadHash, normalized.correlationKey(),
                    normalized.status(), normalized.webhook().getAlerts().size(), traceId, payloadJson);
            if (id != null) {
                return new DeliveryInsert(id, false);
            }
        } catch (DataAccessException ex) {
            log.warn("[AiopsAlert] delivery insert failed, payloadHash={}, reason={}", payloadHash, ex.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "告警 delivery 落库失败");
        }
        AiopsAlertDeliveryVO existing = findDeliveryByPayloadHash(payloadHash);
        if (existing != null) {
            return new DeliveryInsert(existing.getId(), true);
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "告警 delivery 幂等记录不可用");
    }

    private Long processAlert(NormalizedWebhook normalized, AlertmanagerAlertDTO alert,
                              String payloadHash, String traceId) {
        Map<String, String> labels = mergeLabels(normalized.webhook().getCommonLabels(), alert.getLabels());
        String fingerprint = AiopsAlertCanonicalizer.fingerprint("alertmanager", labels);
        LocalDateTime sourceStartedAt = parseTime(alert.getStartsAt(), LocalDateTime.now());
        String eventKey = "alertmanager:" + fingerprint;
        if ("RESOLVED".equals(alert.getStatus()) || "RESOLVED".equals(normalized.status())) {
            return resolveSourceEvent(fingerprint, sourceStartedAt, parseTime(alert.getEndsAt(), LocalDateTime.now()),
                    normalized.correlationKey(), payloadHash);
        }
        String alertName = safe(labels.get("alertname"), 255);
        String service = firstNonBlank(labels.get("service"), labels.get("job"), "unknown-service");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("alertStatus", alert.getStatus());
        metadata.put("payloadHash", payloadHash);
        metadata.put("correlationKey", normalized.correlationKey());
        metadata.put("service", service);
        metadata.put("labels", labels);
        metadata.put("annotations", alert.getAnnotations());
        metadata.put("generatorURL", alert.getGeneratorURL());
        String severity = severity(labels.get("severity"));
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO aiops_event(event_key,fingerprint,correlation_key,occurrence_count,trace_id,severity,source,
                                        title,summary,status,source_started_at,source_ended_at,diagnosis_status,
                                        diagnosis_json,metadata_json,last_seen_at)
                VALUES (?,?,?,1,?,?,?,?,?,'OPEN',?,NULL,'PENDING','{}'::jsonb,?::jsonb,CURRENT_TIMESTAMP)
                ON CONFLICT(event_key) DO UPDATE SET
                    fingerprint=EXCLUDED.fingerprint,
                    correlation_key=EXCLUDED.correlation_key,
                    occurrence_count=aiops_event.occurrence_count+1,
                    trace_id=EXCLUDED.trace_id,
                    severity=EXCLUDED.severity,
                    source=EXCLUDED.source,
                    title=EXCLUDED.title,
                    summary=EXCLUDED.summary,
                    status=CASE WHEN aiops_event.status IN ('RESOLVED','ESCALATED','REJECTED','SOURCE_RECOVERED')
                                THEN 'OPEN' ELSE aiops_event.status END,
                    source_started_at=COALESCE(aiops_event.source_started_at, EXCLUDED.source_started_at),
                    source_ended_at=NULL,
                    resolution_type='',
                    resolved_at=NULL,
                    manual_required=FALSE,
                    escalation_reason='',
                    diagnosis_status='PENDING',
                    diagnosis_json='{}'::jsonb,
                    metadata_json=EXCLUDED.metadata_json,
                    last_seen_at=CURRENT_TIMESTAMP,
                    updated_time=CURRENT_TIMESTAMP
                RETURNING id
                """, Long.class, eventKey, fingerprint, normalized.correlationKey(), traceId,
                severity, "alertmanager", alertName,
                "Alertmanager detected " + (StringUtils.hasText(alertName) ? alertName : "an alert") + " for " + service,
                Timestamp.valueOf(sourceStartedAt), writeJson(metadata));
        return id;
    }

    private Long resolveSourceEvent(String fingerprint, LocalDateTime sourceStartedAt, LocalDateTime sourceEndedAt,
                                    String correlationKey, String payloadHash) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,status,source_started_at FROM aiops_event WHERE fingerprint=?
                """, fingerprint);
        if (rows.isEmpty()) {
            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO aiops_event(event_key,fingerprint,correlation_key,occurrence_count,trace_id,severity,source,
                                            title,summary,status,source_started_at,source_ended_at,resolution_type,
                                            resolved_at,metadata_json,last_seen_at)
                    VALUES (?,?,?,0,?,'INFO','alertmanager','Recovered alert','Alertmanager delivered a resolved notification',
                            'RESOLVED',?,?, 'SOURCE_RECOVERED',CURRENT_TIMESTAMP,?::jsonb,CURRENT_TIMESTAMP)
                    RETURNING id
                    """, Long.class, "alertmanager:" + fingerprint, fingerprint, correlationKey,
                    "aiops-alert-recovery-" + fingerprint.substring(0, 16),
                    Timestamp.valueOf(sourceStartedAt), Timestamp.valueOf(sourceEndedAt),
                    writeJson(Map.of("payloadHash", payloadHash)));
            return id;
        }
        Map<String, Object> current = rows.get(0);
        String status = String.valueOf(current.get("status"));
        Long id = ((Number) current.get("id")).longValue();
        boolean canRecover = SOURCE_RECOVERY_STATES.contains(status)
                && !hasExecutedAction(id);
        jdbcTemplate.update("""
                UPDATE aiops_event
                SET source_ended_at=?, correlation_key=?,
                    status=CASE WHEN ? THEN 'RESOLVED' ELSE status END,
                    resolution_type=CASE WHEN ? THEN 'SOURCE_RECOVERED' ELSE resolution_type END,
                    resolved_at=CASE WHEN ? THEN COALESCE(resolved_at,CURRENT_TIMESTAMP) ELSE resolved_at END,
                    last_seen_at=CURRENT_TIMESTAMP, updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, Timestamp.valueOf(sourceEndedAt), correlationKey, canRecover, canRecover, canRecover, id);
        return id;
    }

    private boolean hasExecutedAction(Long eventId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM aiops_action WHERE event_id=? AND executed=TRUE
                """, Integer.class, eventId);
        return count != null && count > 0;
    }

    private String fingerprintFor(Long eventId) {
        String fingerprint = jdbcTemplate.queryForObject(
                "SELECT fingerprint FROM aiops_event WHERE id=?", String.class, eventId);
        return safe(fingerprint, 64);
    }

    private void updateDeliveryEvents(Long deliveryId, List<Long> eventIds) {
        jdbcTemplate.update("""
                UPDATE aiops_alert_delivery
                SET event_ids_json=?::jsonb, processing_status='EVENTS_PERSISTED', updated_time=CURRENT_TIMESTAMP
                WHERE id=?
                """, writeJson(eventIds), deliveryId);
    }

    private AiopsAlertDeliveryVO findDeliveryByPayloadHash(String payloadHash) {
        List<AiopsAlertDeliveryVO> rows = jdbcTemplate.query("""
                SELECT id,delivery_key,payload_hash,group_key,status,alert_count,processing_status,
                       error_msg,trace_id,event_ids_json::text,payload_json::text,created_time,updated_time
                FROM aiops_alert_delivery WHERE payload_hash=?
                """, this::mapDelivery, payloadHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AiopsAlertDeliveryVO mapDelivery(ResultSet rs, int rowNum) throws SQLException {
        AiopsAlertDeliveryVO vo = new AiopsAlertDeliveryVO();
        vo.setId(rs.getLong("id"));
        vo.setDeliveryKey(rs.getString("delivery_key"));
        vo.setPayloadHash(rs.getString("payload_hash"));
        vo.setGroupKey(rs.getString("group_key"));
        vo.setStatus(rs.getString("status"));
        vo.setAlertCount(rs.getInt("alert_count"));
        vo.setProcessingStatus(rs.getString("processing_status"));
        vo.setErrorMsg(rs.getString("error_msg"));
        vo.setTraceId(rs.getString("trace_id"));
        vo.setEventIds(readLongList(rs.getString("event_ids_json")));
        vo.setPayloadJson(rs.getString("payload_json"));
        vo.setCreatedTime(toLocalDateTime(rs.getTimestamp("created_time")));
        vo.setUpdatedTime(toLocalDateTime(rs.getTimestamp("updated_time")));
        return vo;
    }

    private List<Long> readLongList(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            Long[] values = objectMapper.readValue(json, Long[].class);
            return values == null ? new ArrayList<>() : new ArrayList<>(List.of(values));
        } catch (JsonProcessingException ex) {
            return new ArrayList<>();
        }
    }

    private AiopsAlertWebhookVO webhookResult(AiopsAlertDeliveryVO delivery, boolean duplicate) {
        AiopsAlertWebhookVO result = new AiopsAlertWebhookVO();
        result.setDeliveryId(delivery.getId());
        result.setDuplicate(duplicate);
        result.setAcceptedAlerts(delivery.getAlertCount() == null ? 0 : delivery.getAlertCount());
        result.setEventIds(delivery.getEventIds());
        result.setTraceId(delivery.getTraceId());
        result.setPayloadHash(delivery.getPayloadHash());
        result.setProcessingStatus(delivery.getProcessingStatus());
        return result;
    }

    private void validateToken(String presentedToken) {
        String expected = properties.getWebhookToken();
        if (!properties.isEnabled()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "AIOps Alertmanager webhook 未启用");
        }
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(presentedToken)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "AIOps webhook token 未配置或无效");
        }
        byte[] expectedBytes = expected.trim().getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = presentedToken.trim().getBytes(StandardCharsets.UTF_8);
        if (expectedBytes.length != actualBytes.length
                || !MessageDigest.isEqual(expectedBytes, actualBytes)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "AIOps webhook token 无效");
        }
    }

    private Map<String, String> mergeLabels(Map<String, String> common, Map<String, String> alert) {
        Map<String, String> merged = new TreeMap<>();
        if (common != null) {
            merged.putAll(common);
        }
        if (alert != null) {
            merged.putAll(alert);
        }
        return merged;
    }

    private Map<String, String> boundedMap(Map<String, String> source, String field) {
        if (source == null) {
            return new LinkedHashMap<>();
        }
        if (source.size() > boundedMax(properties.getMaxMapEntries(), 32)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, field + "数量超过限制");
        }
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = required(entry.getKey(), 128, field + ".key");
            String value = required(entry.getValue(), properties.getMaxStringLength(), field + ".value");
            result.put(key, value);
        }
        return result;
    }

    private String normalizedStatus(String value, List<String> allowed, String field) {
        String normalized = required(value, 32, field).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, field + "必须是 firing 或 resolved");
        }
        return normalized;
    }

    private String required(String value, int limit, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, field + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > limit) {
            throw new BusinessException(ResultCode.BAD_REQUEST, field + "长度超过限制");
        }
        return normalized;
    }

    private String safe(String value, int limit) {
        return safe(value, limit, "字段");
    }

    private String safe(String value, int limit, String field) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() > limit) {
            throw new BusinessException(ResultCode.BAD_REQUEST, field + "长度超过限制");
        }
        return normalized;
    }

    private LocalDateTime parseTime(String value, LocalDateTime fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "告警时间格式不合法");
        }
    }

    private String severity(String value) {
        return switch (safe(value, 32).toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "FATAL" -> "CRITICAL";
            case "WARNING", "WARN" -> "WARNING";
            default -> "INFO";
        };
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return safe(first, 128);
        }
        if (StringUtils.hasText(second)) {
            return safe(second, 128);
        }
        return fallback;
    }

    private int boundedMax(int value, int fallback) {
        return value <= 0 ? fallback : Math.min(value, fallback);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "告警数据无法序列化");
        }
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record NormalizedWebhook(AlertmanagerWebhookDTO webhook, String status, String correlationKey) {
    }

    private record DeliveryInsert(Long id, boolean duplicate) {
    }

    private record PersistedAlert(Long deliveryId, List<Long> eventIds, boolean duplicate) {
    }
}
