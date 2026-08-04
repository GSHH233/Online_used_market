package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ProductRiskOutcomeRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.PublishAssistFeedbackRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.PublishAssistRecordQueryDTO;
import org.example.online_used_marketplace.modules.ai.service.PublishAssistFeedbackService;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductRiskOutcomeVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistMetricsVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistRecordVO;
import org.example.online_used_marketplace.modules.product.dto.ProductCreateDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "mysqlTransactionManager")
public class PublishAssistFeedbackServiceImpl implements PublishAssistFeedbackService {

    private static final Set<String> ADOPTION_TYPES = Set.of("APPLIED", "PARTIAL", "IGNORED");
    private static final Set<String> ADOPTABLE_FIELDS = Set.of("title", "description", "price", "riskAdvice");
    private static final Set<String> OUTCOME_TYPES = Set.of(
            "PENDING", "PASSED", "REJECTED", "TAKEN_DOWN", "COMPLAINT_CONFIRMED");
    private static final Set<String> OUTCOME_SOURCES = Set.of("AUTO_REVIEW", "MANUAL_REVIEW", "COMPLAINT");

    @Qualifier("mysqlJdbcTemplate")
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String modelName;

    @Override
    public void recordAssist(String assistId, String traceId, Long userId,
                             ProductPublishAssistRequestDTO request, ProductPublishAssistVO result) {
        if (!StringUtils.hasText(assistId) || !StringUtils.hasText(traceId) || userId == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "发布建议缺少追踪标识");
        }
        jdbcTemplate.update("""
                INSERT INTO ai_publish_assist_record(
                    assist_id,user_id,trace_id,request_json,suggestion_json,evidence_json,
                    risk_level,model_name,prompt_version)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, assistId, userId, traceId, writeJson(request), writeJson(result),
                writeJson(result.getRiskEvidence()), riskLevel(result), safe(modelName, 128), "publish-assist-v1");
    }

    @Override
    public PublishAssistRecordVO feedback(String assistId, Long userId, PublishAssistFeedbackRequestDTO request) {
        requireOwned(assistId, userId);
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "反馈请求不能为空");
        }
        String adoptionType = normalize(request.getAdoptionType());
        if (!ADOPTION_TYPES.contains(adoptionType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "adoptionType仅支持APPLIED/PARTIAL/IGNORED");
        }
        List<String> adoptedFields = normalizeFields(request.getAdoptedFields());
        if ("IGNORED".equals(adoptionType) && !adoptedFields.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "IGNORED不能包含已采纳字段");
        }
        if ("PARTIAL".equals(adoptionType) && adoptedFields.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "PARTIAL至少包含一个已采纳字段");
        }
        validateProductOwner(request.getProductId(), userId);
        jdbcTemplate.update("""
                INSERT INTO ai_publish_assist_feedback(
                    assist_id,product_id,user_id,adoption_type,adopted_fields_json,feedback_reason)
                VALUES (?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    product_id=COALESCE(VALUES(product_id),product_id),
                    adoption_type=VALUES(adoption_type),
                    adopted_fields_json=VALUES(adopted_fields_json),
                    feedback_reason=VALUES(feedback_reason),
                    updated_time=CURRENT_TIMESTAMP
                """, assistId, request.getProductId(), userId, adoptionType,
                writeJson(adoptedFields), safe(request.getReason(), 500));
        return getOwned(assistId, userId);
    }

    @Override
    public PublishAssistRecordVO getOwned(String assistId, Long userId) {
        return requireOwned(assistId, userId);
    }

    @Override
    public PublishAssistRecordVO getInternal(String assistId) {
        if (!StringUtils.hasText(assistId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "assistId不合法");
        }
        List<PublishAssistRecordVO> rows = jdbcTemplate.query("""
                SELECT r.assist_id,r.user_id,r.trace_id,r.risk_level,r.request_json,r.suggestion_json,
                       r.evidence_json,r.created_time,r.updated_time,f.product_id,f.adoption_type,
                       f.adopted_fields_json,f.feedback_reason
                FROM ai_publish_assist_record r
                LEFT JOIN ai_publish_assist_feedback f ON f.assist_id=r.assist_id
                WHERE r.assist_id=?
                """, this::mapRecord, assistId.trim());
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "发布助手记录不存在");
        }
        return rows.get(0);
    }

    @Override
    public List<PublishAssistRecordVO> listRecords(PublishAssistRecordQueryDTO query) {
        PublishAssistRecordQueryDTO condition = query == null ? new PublishAssistRecordQueryDTO() : query;
        StringBuilder sql = new StringBuilder("""
                SELECT r.assist_id,r.user_id,r.trace_id,r.risk_level,r.request_json,r.suggestion_json,
                       r.evidence_json,r.created_time,r.updated_time,f.product_id,f.adoption_type,
                       f.adopted_fields_json,f.feedback_reason
                FROM ai_publish_assist_record r
                LEFT JOIN ai_publish_assist_feedback f ON f.assist_id=r.assist_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (condition.getUserId() != null && condition.getUserId() > 0) {
            sql.append(" AND r.user_id=?");
            args.add(condition.getUserId());
        }
        if (condition.getProductId() != null && condition.getProductId() > 0) {
            sql.append(" AND f.product_id=?");
            args.add(condition.getProductId());
        }
        if (StringUtils.hasText(condition.getRiskLevel())) {
            sql.append(" AND r.risk_level=?");
            args.add(normalize(condition.getRiskLevel()));
        }
        if (StringUtils.hasText(condition.getAdoptionType())) {
            String adoptionType = normalize(condition.getAdoptionType());
            if (!ADOPTION_TYPES.contains(adoptionType)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "adoptionType仅支持APPLIED/PARTIAL/IGNORED");
            }
            sql.append(" AND f.adoption_type=?");
            args.add(adoptionType);
        }
        if (condition.getFrom() != null) {
            sql.append(" AND r.created_time>=?");
            args.add(Timestamp.valueOf(condition.getFrom()));
        }
        if (condition.getTo() != null) {
            sql.append(" AND r.created_time<?");
            args.add(Timestamp.valueOf(condition.getTo()));
        }
        int limit = condition.getLimit() == null ? 20 : Math.min(Math.max(condition.getLimit(), 1), 100);
        sql.append(" ORDER BY r.created_time DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapRecord, args.toArray());
    }

    @Override
    public void linkProduct(String assistId, Long userId, Long productId, ProductCreateDTO product) {
        if (!StringUtils.hasText(assistId)) {
            return;
        }
        PublishAssistRecordVO record = requireOwned(assistId, userId);
        String adoptionType = record.getAdoptionType();
        List<String> fields = record.getAdoptedFields();
        if (StringUtils.hasText(product.getAssistAdoptionType())) {
            adoptionType = normalize(product.getAssistAdoptionType());
            if (!ADOPTION_TYPES.contains(adoptionType)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "assistAdoptionType不合法");
            }
            fields = normalizeFields(product.getAssistAdoptedFields());
        } else if (!StringUtils.hasText(adoptionType)) {
            fields = inferFields(record.getSuggestionJson(), product);
            adoptionType = fields.isEmpty() ? "IGNORED"
                    : fields.containsAll(List.of("title", "description")) ? "APPLIED" : "PARTIAL";
        }
        if ("IGNORED".equals(adoptionType)) {
            fields = List.of();
        }
        jdbcTemplate.update("""
                INSERT INTO ai_publish_assist_feedback(
                    assist_id,product_id,user_id,adoption_type,adopted_fields_json,final_content_hash,feedback_reason)
                VALUES (?,?,?,?,?,?, '')
                ON DUPLICATE KEY UPDATE
                    product_id=VALUES(product_id),
                    adoption_type=VALUES(adoption_type),
                    adopted_fields_json=VALUES(adopted_fields_json),
                    final_content_hash=VALUES(final_content_hash),
                    updated_time=CURRENT_TIMESTAMP
                """, assistId, productId, userId, adoptionType, writeJson(fields), contentHash(product));
    }

    @Override
    public ProductRiskOutcomeVO recordOutcome(Long productId, Long operatorId, ProductRiskOutcomeRequestDTO request) {
        if (productId == null || productId <= 0 || operatorId == null || operatorId <= 0 || request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "风险结果请求不完整");
        }
        Long productCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pms_product WHERE id=? AND is_deleted=0", Long.class, productId);
        if (productCount == null || productCount == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
        String outcomeType = normalize(request.getOutcomeType());
        String outcomeSource = normalize(request.getOutcomeSource());
        if (!OUTCOME_TYPES.contains(outcomeType) || !OUTCOME_SOURCES.contains(outcomeSource)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "风险结果类型或来源不合法");
        }
        boolean isFinal = Boolean.TRUE.equals(request.getIsFinal());
        String assistId = jdbcTemplate.query("""
                SELECT assist_id FROM ai_publish_assist_feedback
                WHERE product_id=? ORDER BY updated_time DESC LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null, productId);
        if (isFinal) {
            jdbcTemplate.update("UPDATE ai_product_risk_outcome SET is_final=0 WHERE product_id=? AND is_final=1", productId);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ai_product_risk_outcome(
                        product_id,assist_id,outcome_type,outcome_source,reason_code,reason,operator_id,is_final)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, productId);
            statement.setString(2, assistId);
            statement.setString(3, outcomeType);
            statement.setString(4, outcomeSource);
            statement.setString(5, safe(request.getReasonCode(), 64));
            statement.setString(6, safe(request.getReason(), 1000));
            statement.setLong(7, operatorId);
            statement.setBoolean(8, isFinal);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return getOutcome(key == null ? null : key.longValue());
    }

    @Override
    public PublishAssistMetricsVO metrics(LocalDateTime from, LocalDateTime to) {
        LocalDateTime end = to == null ? LocalDateTime.now() : to;
        LocalDateTime start = from == null ? end.minusDays(30) : from;
        if (!start.isBefore(end)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "from必须早于to");
        }
        Map<String, Object> adoption = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) generated_count,
                       SUM(CASE WHEN f.id IS NOT NULL THEN 1 ELSE 0 END) feedback_count,
                       SUM(CASE WHEN f.adoption_type='APPLIED' THEN 1 ELSE 0 END) applied_count,
                       SUM(CASE WHEN f.adoption_type='PARTIAL' THEN 1 ELSE 0 END) partial_count,
                       SUM(CASE WHEN f.adoption_type='IGNORED' THEN 1 ELSE 0 END) ignored_count
                FROM ai_publish_assist_record r
                LEFT JOIN ai_publish_assist_feedback f ON f.assist_id=r.assist_id
                WHERE r.created_time>=? AND r.created_time<?
                """, Timestamp.valueOf(start), Timestamp.valueOf(end));
        Map<String, Object> risk = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) flagged_with_outcome_count,
                       SUM(CASE WHEN o.outcome_type IN ('REJECTED','TAKEN_DOWN','COMPLAINT_CONFIRMED') THEN 1 ELSE 0 END)
                           confirmed_risk_count,
                       SUM(CASE WHEN o.outcome_type='PASSED' THEN 1 ELSE 0 END) passed_risk_count
                FROM ai_publish_assist_record r
                JOIN ai_publish_assist_feedback f ON f.assist_id=r.assist_id
                JOIN ai_product_risk_outcome o ON o.product_id=f.product_id AND o.is_final=1
                WHERE r.created_time>=? AND r.created_time<? AND r.risk_level IN ('MEDIUM','HIGH')
                """, Timestamp.valueOf(start), Timestamp.valueOf(end));
        long generated = value(adoption, "generated_count");
        long feedback = value(adoption, "feedback_count");
        long applied = value(adoption, "applied_count");
        long partial = value(adoption, "partial_count");
        long ignored = value(adoption, "ignored_count");
        long flagged = value(risk, "flagged_with_outcome_count");
        long confirmed = value(risk, "confirmed_risk_count");
        long passed = value(risk, "passed_risk_count");

        PublishAssistMetricsVO vo = new PublishAssistMetricsVO();
        vo.setFrom(start);
        vo.setTo(end);
        vo.setGeneratedCount(generated);
        vo.setFeedbackCount(feedback);
        vo.setAppliedCount(applied);
        vo.setPartialCount(partial);
        vo.setIgnoredCount(ignored);
        vo.setFlaggedWithOutcomeCount(flagged);
        vo.setConfirmedRiskCount(confirmed);
        vo.setPassedRiskCount(passed);
        vo.setFeedbackCoverage(rate(feedback, generated));
        vo.setAdoptionRate(rate(applied + partial, feedback));
        vo.setFullAdoptionRate(rate(applied, feedback));
        vo.setRiskPrecision(rate(confirmed, flagged));
        vo.setFalsePositiveRate(rate(passed, flagged));
        return vo;
    }

    private PublishAssistRecordVO requireOwned(String assistId, Long userId) {
        if (!StringUtils.hasText(assistId) || userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "assistId或用户不合法");
        }
        List<PublishAssistRecordVO> rows = jdbcTemplate.query("""
                SELECT r.assist_id,r.user_id,r.trace_id,r.risk_level,r.request_json,r.suggestion_json,
                       r.evidence_json,r.created_time,r.updated_time,f.product_id,f.adoption_type,
                       f.adopted_fields_json,f.feedback_reason
                FROM ai_publish_assist_record r
                LEFT JOIN ai_publish_assist_feedback f ON f.assist_id=r.assist_id
                WHERE r.assist_id=? AND r.user_id=?
                """, this::mapRecord, assistId.trim(), userId);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "发布建议不存在");
        }
        return rows.get(0);
    }

    private PublishAssistRecordVO mapRecord(ResultSet rs, int rowNum) throws SQLException {
        PublishAssistRecordVO vo = new PublishAssistRecordVO();
        vo.setAssistId(rs.getString("assist_id"));
        vo.setUserId(rs.getLong("user_id"));
        vo.setTraceId(rs.getString("trace_id"));
        vo.setRiskLevel(rs.getString("risk_level"));
        vo.setRequestJson(rs.getString("request_json"));
        vo.setSuggestionJson(rs.getString("suggestion_json"));
        vo.setEvidenceJson(rs.getString("evidence_json"));
        long productId = rs.getLong("product_id");
        vo.setProductId(rs.wasNull() ? null : productId);
        vo.setAdoptionType(rs.getString("adoption_type"));
        vo.setAdoptedFields(readStringList(rs.getString("adopted_fields_json")));
        vo.setFeedbackReason(rs.getString("feedback_reason"));
        Timestamp created = rs.getTimestamp("created_time");
        Timestamp updated = rs.getTimestamp("updated_time");
        vo.setCreatedTime(created == null ? null : created.toLocalDateTime());
        vo.setUpdatedTime(updated == null ? null : updated.toLocalDateTime());
        return vo;
    }

    private ProductRiskOutcomeVO getOutcome(Long id) {
        if (id == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "风险结果保存失败");
        }
        return jdbcTemplate.queryForObject("""
                SELECT id,product_id,assist_id,outcome_type,outcome_source,reason_code,reason,
                       operator_id,is_final,created_time
                FROM ai_product_risk_outcome WHERE id=?
                """, (rs, rowNum) -> {
            ProductRiskOutcomeVO vo = new ProductRiskOutcomeVO();
            vo.setId(rs.getLong("id"));
            vo.setProductId(rs.getLong("product_id"));
            vo.setAssistId(rs.getString("assist_id"));
            vo.setOutcomeType(rs.getString("outcome_type"));
            vo.setOutcomeSource(rs.getString("outcome_source"));
            vo.setReasonCode(rs.getString("reason_code"));
            vo.setReason(rs.getString("reason"));
            vo.setOperatorId(rs.getLong("operator_id"));
            vo.setIsFinal(rs.getBoolean("is_final"));
            vo.setCreatedTime(rs.getTimestamp("created_time").toLocalDateTime());
            return vo;
        }, id);
    }

    private void validateProductOwner(Long productId, Long userId) {
        if (productId == null) {
            return;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pms_product WHERE id=? AND seller_id=? AND is_deleted=0",
                Long.class, productId, userId);
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "商品不存在");
        }
    }

    private List<String> inferFields(String suggestionJson, ProductCreateDTO product) {
        List<String> fields = new ArrayList<>();
        try {
            JsonNode optimized = objectMapper.readTree(suggestionJson).path("optimizeResult");
            if (product.getTitle().equals(optimized.path("optimizedTitle").asText())) {
                fields.add("title");
            }
            String description = product.getDescription() == null ? "" : product.getDescription();
            if (description.equals(optimized.path("optimizedDescription").asText())) {
                fields.add("description");
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return fields;
    }

    private List<String> normalizeFields(List<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = value == null ? "" : value.trim();
                if (!ADOPTABLE_FIELDS.contains(normalized)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的采纳字段：" + normalized);
                }
                out.add(normalized);
            }
        }
        return new ArrayList<>(out);
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readerForListOf(String.class).readValue(json);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private String riskLevel(ProductPublishAssistVO result) {
        if (Boolean.FALSE.equals(result.getCanPublish())) {
            return "HIGH";
        }
        int score = result.getRiskScore() == null ? 0 : result.getRiskScore();
        if (score < 60) {
            return "HIGH";
        }
        return score < 80 ? "MEDIUM" : "LOW";
    }

    private String contentHash(ProductCreateDTO product) {
        try {
            String canonical = product.getTitle() + "\n" + String.valueOf(product.getDescription()) + "\n" + product.getPrice();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "商品内容摘要计算失败");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "发布建议序列化失败");
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String safe(String value, int limit) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private long value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : numerator / (double) denominator;
    }
}
