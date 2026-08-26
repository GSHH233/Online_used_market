package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ChangeSpecialistAgent implements AiopsSpecialistAgent {

    private final JdbcTemplate jdbcTemplate;

    public ChangeSpecialistAgent(@Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SpecialistType type() {
        return SpecialistType.CHANGE;
    }

    @Override
    public SpecialistResult investigate(AlertInvestigationContext context) {
        if (jdbcTemplate == null) {
            return SpecialistResult.degraded(type(), "change_source_unavailable", "Change database unavailable");
        }
        Instant observedAt = context == null ? Instant.now() : context.effectiveObservedAt();
        Instant from = observedAt.minusSeconds((context == null ? 30 : context.effectiveWindowMinutes()) * 60L);
        String service = safeService(context == null ? null : context.getService());
        StringBuilder sql = new StringBuilder("""
                SELECT id,service,environment,change_type,version,trace_id,summary,
                       metadata_json::text AS metadata_json,occurred_at
                FROM aiops_change_event
                WHERE occurred_at >= ? AND occurred_at <= ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(observedAt));
        if (!service.isBlank()) {
            sql.append(" AND service = ?");
            args.add(service);
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");
        args.add(50);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getObject("id"));
                row.put("service", rs.getString("service"));
                row.put("environment", rs.getString("environment"));
                row.put("changeType", rs.getString("change_type"));
                row.put("version", rs.getString("version"));
                row.put("traceId", rs.getString("trace_id"));
                row.put("summary", rs.getString("summary"));
                row.put("metadataJson", rs.getString("metadata_json"));
                row.put("occurredAt", rs.getTimestamp("occurred_at"));
                return row;
            }, args.toArray());
            if (rows == null || rows.isEmpty()) {
                return SpecialistResult.builder()
                        .specialist(type())
                        .status(SpecialistStatus.DEGRADED)
                        .missingEvidence(List.of("change_events"))
                        .errorMessage("no recent change events")
                        .build();
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("resultCount", rows.size());
            attributes.put("service", service);
            attributes.put("from", from.toString());
            attributes.put("to", observedAt.toString());
            Evidence evidence = Evidence.observed(
                    EvidenceIds.forSpecialist(type(), context),
                    type(),
                    "aiops_change_event",
                    "service=" + service + ",windowMinutes="
                            + (context == null ? 30 : context.effectiveWindowMinutes()),
                    "Recent change query returned " + rows.size() + " event(s)",
                    observedAt,
                    attributes);
            return SpecialistResult.completed(type(), List.of(evidence));
        } catch (Exception ex) {
            log.debug("change specialist degraded: {}", ex.getMessage());
            return SpecialistResult.degraded(type(), "change_events", safeMessage(ex));
        }
    }

    private String safeService(String value) {
        if (value == null || value.isBlank() || value.length() > 100
                || !value.matches("[A-Za-z0-9_.:-]+")) {
            return "";
        }
        return value.trim();
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }
}
