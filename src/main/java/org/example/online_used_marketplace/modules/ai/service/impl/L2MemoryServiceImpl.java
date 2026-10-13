package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.entity.L2MemoryRecord;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryDeleteCommand;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryMutationResult;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallExclusion;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallQuery;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallResult;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryWriteCommand;
import org.example.online_used_marketplace.modules.ai.service.EmbeddingService;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;
import org.example.online_used_marketplace.modules.ai.vo.L2SessionSummarySnapshot;
import org.example.online_used_marketplace.modules.ai.vo.L2SessionSummaryWriteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class L2MemoryServiceImpl implements L2MemoryService {

    private static final double RELEVANCE_WEIGHT = 0.85D;
    private static final double IMPORTANCE_WEIGHT = 0.15D;
    private static final double DEFAULT_L2_IMPORTANCE = 0.3D;
    private static final String SESSION_SUMMARY_MEMORY_TYPE = "SESSION_SUMMARY";
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String SUPERSEDED_STATUS = "SUPERSEDED";
    private static final String DELETED_STATUS = "DELETED";
    private static final Pattern LEXICAL_TOKEN_PATTERN = Pattern.compile(
            "[\\p{IsHan}]+|[a-z0-9][a-z0-9._+-]*");

    private final org.example.online_used_marketplace.modules.ai.mapper.l2.L2MemoryRecordMapper l2MemoryRecordMapper;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate mysqlJdbcTemplate;

    @Value("${ai.memory.l2.recall.candidate-limit:1000}")
    private int configuredCandidateLimit = 1000;

    @Value("${ai.memory.l2.recall.audit-exclusion-limit:100}")
    private int configuredAuditExclusionLimit = 100;

    @Autowired
    public L2MemoryServiceImpl(
            org.example.online_used_marketplace.modules.ai.mapper.l2.L2MemoryRecordMapper l2MemoryRecordMapper,
            EmbeddingService embeddingService,
            ObjectMapper objectMapper,
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate) {
        this.l2MemoryRecordMapper = l2MemoryRecordMapper;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
    }

    L2MemoryServiceImpl(
            org.example.online_used_marketplace.modules.ai.mapper.l2.L2MemoryRecordMapper l2MemoryRecordMapper,
            EmbeddingService embeddingService,
            ObjectMapper objectMapper) {
        this(l2MemoryRecordMapper, embeddingService, objectMapper, null);
    }

    @Override
    public void saveMemory(String userId, String sessionId, String memoryType, String content, Double importance) {
        saveMemory(userId, sessionId, memoryType, content, importance, "{}");
    }

    @Override
    public void saveMemory(String userId, String sessionId, String memoryType, String content, Double importance, String metadataJson) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(content)) {
            return;
        }
        L2MemoryRecord record = new L2MemoryRecord();
        record.setUserId(userId.trim());
        record.setSessionId(StringUtils.hasText(sessionId) ? sessionId.trim() : "");
        record.setMemoryType(StringUtils.hasText(memoryType) ? memoryType.trim().toUpperCase(Locale.ROOT) : "FACT");
        record.setContent(content.trim());
        record.setImportance(normalize01(importance == null ? DEFAULT_L2_IMPORTANCE : importance));
        record.setEmbeddingJson(toEmbeddingJson(content.trim()));
        record.setMetadataJson(StringUtils.hasText(metadataJson) ? metadataJson.trim() : "{}");
        initializeLifecycle(record);
        record.setCreatedTime(LocalDateTime.now());
        record.setUpdatedTime(LocalDateTime.now());
        l2MemoryRecordMapper.insert(record);
    }

    @Override
    @Transactional(transactionManager = "mysqlTransactionManager")
    public L2MemoryMutationResult upsertSlotMemory(L2MemoryWriteCommand command) {
        SlotIdentity slot = normalizeWriteCommand(command);
        if (shouldSkipLongTermMemory(slot)) {
            return new L2MemoryMutationResult(null, "SKIPPED", "NOT_STORED", 0L);
        }
        String idempotencyKey = idempotencyKey(slot);
        String payloadHash = sha256(String.join("|",
                "UPSERT", slot.memoryType(), slot.content(), slot.metadataJson(),
                slot.scopeType(), slot.scopeKey(), slot.slotKey()));

        // Structured preferences are read through the slot projection; avoid a remote
        // embedding call on this write-critical path and retain lexical recall fallback.
        String embeddingJson = "PREFERENCE".equals(slot.memoryType())
                ? "[]" : toEmbeddingJson(slot.content());
        SlotState state = lockSlot(slot);
        L2MemoryRecord replay = findByIdempotencyForUpdate(slot.userId(), idempotencyKey);
        if (replay != null) {
            return replayResult(replay, payloadHash);
        }

        LocalDateTime now = LocalDateTime.now();
        L2MemoryRecord record = new L2MemoryRecord();
        record.setUserId(slot.userId());
        record.setSessionId(slot.sessionId());
        record.setMemoryType(slot.memoryType());
        record.setContent(slot.content());
        record.setImportance(slot.importance());
        record.setEmbeddingJson(embeddingJson);
        record.setMetadataJson(slot.metadataJson());
        record.setSourceEventId(slot.sourceEventId());
        record.setIdempotencyKey(idempotencyKey);
        record.setPayloadHash(payloadHash);
        record.setSourceType(slot.sourceType());
        record.setAssertionStatus(slot.assertionStatus());
        record.setScopeType(slot.scopeType());
        record.setScopeKey(slot.scopeKey());
        record.setSlotKey(slot.slotKey());
        record.setRevision(state.revision() + 1L);
        record.setStatus(ACTIVE_STATUS);
        record.setSupersedesId(state.currentRecordId());
        record.setValidFrom(now);
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        l2MemoryRecordMapper.insert(record);

        if (state.currentRecordId() != null) {
            mysqlJdbcTemplate.update("""
                    UPDATE ai_l2_memory_record
                    SET status = ?, valid_until = ?, updated_time = ?
                    WHERE id = ? AND user_id = ? AND status = ?
                    """, SUPERSEDED_STATUS, now, now, state.currentRecordId(), slot.userId(), ACTIVE_STATUS);
        }
        mysqlJdbcTemplate.update("""
                UPDATE ai_memory_slot
                SET current_record_id = ?, revision = ?, updated_time = ?
                WHERE id = ?
                """, record.getId(), record.getRevision(), now, state.slotId());
        String disposition = state.currentRecordId() == null ? "CREATED" : "UPDATED";
        return new L2MemoryMutationResult(record.getId(), disposition, ACTIVE_STATUS, record.getRevision());
    }

    @Override
    @Transactional(transactionManager = "mysqlTransactionManager")
    public L2MemoryMutationResult deleteSlotMemory(L2MemoryDeleteCommand command) {
        SlotIdentity slot = normalizeDeleteCommand(command);
        String idempotencyKey = idempotencyKey(slot);
        String payloadHash = sha256(String.join("|", "DELETE", slot.memoryType(),
                slot.scopeType(), slot.scopeKey(), slot.slotKey()));

        SlotState state = lockSlot(slot);
        L2MemoryRecord replay = findByIdempotencyForUpdate(slot.userId(), idempotencyKey);
        if (replay != null) {
            return replayResult(replay, payloadHash);
        }

        LocalDateTime now = LocalDateTime.now();
        if (state.currentRecordId() != null) {
            mysqlJdbcTemplate.update("""
                    UPDATE ai_l2_memory_record
                    SET status = ?, valid_until = ?, deleted_at = ?, updated_time = ?
                    WHERE id = ? AND user_id = ? AND status = ?
                    """, DELETED_STATUS, now, now, now, state.currentRecordId(), slot.userId(), ACTIVE_STATUS);
        }

        L2MemoryRecord tombstone = new L2MemoryRecord();
        tombstone.setUserId(slot.userId());
        tombstone.setSessionId("");
        tombstone.setMemoryType(slot.memoryType());
        tombstone.setContent("TOMBSTONE|" + slot.memoryType() + "|" + slot.slotKey());
        tombstone.setImportance(0D);
        tombstone.setEmbeddingJson("[]");
        tombstone.setMetadataJson("{}");
        tombstone.setSourceEventId(slot.sourceEventId());
        tombstone.setIdempotencyKey(idempotencyKey);
        tombstone.setPayloadHash(payloadHash);
        tombstone.setSourceType("USER_REQUEST");
        tombstone.setAssertionStatus("CONFIRMED");
        tombstone.setScopeType(slot.scopeType());
        tombstone.setScopeKey(slot.scopeKey());
        tombstone.setSlotKey(slot.slotKey());
        tombstone.setRevision(state.revision() + 1L);
        tombstone.setStatus(DELETED_STATUS);
        tombstone.setSupersedesId(state.currentRecordId());
        tombstone.setValidFrom(now);
        tombstone.setValidUntil(now);
        tombstone.setDeletedAt(now);
        tombstone.setCreatedTime(now);
        tombstone.setUpdatedTime(now);
        l2MemoryRecordMapper.insert(tombstone);

        mysqlJdbcTemplate.update("""
                UPDATE ai_memory_slot
                SET current_record_id = NULL, revision = ?, updated_time = ?
                WHERE id = ?
                """, tombstone.getRevision(), now, state.slotId());
        return new L2MemoryMutationResult(tombstone.getId(), "DELETED", DELETED_STATUS, tombstone.getRevision());
    }

    @Override
    public List<L2MemoryHitVO> listActiveSlotMemories(String userId, String memoryType) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(memoryType) || mysqlJdbcTemplate == null) {
            return List.of();
        }
        List<Long> ids = mysqlJdbcTemplate.query("""
                SELECT r.id
                FROM ai_memory_slot s
                JOIN ai_l2_memory_record r ON r.id = s.current_record_id
                WHERE s.owner_user_id = ?
                  AND s.memory_type = ?
                  AND r.user_id = s.owner_user_id
                  AND r.status = ?
                  AND r.deleted_at IS NULL
                  AND (r.valid_until IS NULL OR r.valid_until > CURRENT_TIMESTAMP(6))
                ORDER BY s.updated_time DESC, s.id DESC
                """, (rs, rowNum) -> rs.getLong(1), userId.trim(), memoryType.trim().toUpperCase(Locale.ROOT), ACTIVE_STATUS);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<L2MemoryHitVO> result = new ArrayList<>();
        for (Long id : ids) {
            L2MemoryRecord record = l2MemoryRecordMapper.selectById(id);
            if (isActive(record)) {
                result.add(toHit(record));
            }
        }
        return result;
    }

    @Override
    public List<L2MemoryHitVO> listActiveMemoriesByType(String userId, String memoryType, int limit) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(memoryType)) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 1000);
        LambdaQueryWrapper<L2MemoryRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(L2MemoryRecord::getUserId, userId.trim())
                .eq(L2MemoryRecord::getMemoryType, memoryType.trim().toUpperCase(Locale.ROOT))
                .eq(L2MemoryRecord::getStatus, ACTIVE_STATUS)
                .isNull(L2MemoryRecord::getDeletedAt)
                .and(w -> w.isNull(L2MemoryRecord::getValidUntil)
                        .or().gt(L2MemoryRecord::getValidUntil, LocalDateTime.now()))
                .orderByDesc(L2MemoryRecord::getUpdatedTime)
                .orderByDesc(L2MemoryRecord::getId)
                .last("LIMIT " + safeLimit);
        List<L2MemoryRecord> records = l2MemoryRecordMapper.selectList(wrapper);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream().filter(this::isActive).map(this::toHit).toList();
    }

    @Override
    public L2MemoryHitVO latestSessionSummary(String userId, String sessionId) {
        L2SessionSummarySnapshot snapshot = latestSessionSummarySnapshot(userId, sessionId);
        return snapshot == null ? null : snapshot.memory();
    }

    @Override
    public L2SessionSummarySnapshot latestSessionSummarySnapshot(String userId, String sessionId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId)) {
            return null;
        }

        if (mysqlJdbcTemplate != null) {
            List<Map<String, Object>> slots = mysqlJdbcTemplate.queryForList("""
                    SELECT current_record_id, revision
                    FROM ai_memory_slot
                    WHERE owner_user_id = ?
                      AND scope_type = 'SESSION'
                      AND scope_key = ?
                      AND memory_type = ?
                      AND slot_key = ''
                    LIMIT 1
                    """, userId.trim(), sessionId.trim(), SESSION_SUMMARY_MEMORY_TYPE);
            if (!slots.isEmpty()) {
                Map<String, Object> slot = slots.get(0);
                Number currentRecordId = (Number) slot.get("current_record_id");
                Number revision = (Number) slot.get("revision");
                if (currentRecordId != null) {
                    L2MemoryRecord current = l2MemoryRecordMapper.selectById(currentRecordId.longValue());
                    if (current != null
                            && userId.trim().equals(current.getUserId())
                            && sessionId.trim().equals(current.getSessionId())
                            && SESSION_SUMMARY_MEMORY_TYPE.equals(current.getMemoryType())
                            && isActive(current)) {
                        return new L2SessionSummarySnapshot(toHit(current), revision == null ? 0L : revision.longValue());
                    }
                    log.warn("L2 session summary slot points to an invalid record, owner={}, session={}",
                            userId.trim(), sessionId.trim());
                    return null;
                }
            }
        }

        LambdaQueryWrapper<L2MemoryRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(L2MemoryRecord::getUserId, userId.trim())
                .eq(L2MemoryRecord::getSessionId, sessionId.trim())
                .eq(L2MemoryRecord::getMemoryType, SESSION_SUMMARY_MEMORY_TYPE)
                .eq(L2MemoryRecord::getStatus, ACTIVE_STATUS)
                .and(w -> w.isNull(L2MemoryRecord::getValidUntil)
                        .or().gt(L2MemoryRecord::getValidUntil, LocalDateTime.now()))
                .orderByDesc(L2MemoryRecord::getUpdatedTime)
                .orderByDesc(L2MemoryRecord::getId)
                .last("LIMIT 1");

        L2MemoryRecord legacyRecord = l2MemoryRecordMapper.selectOne(wrapper);
        L2MemoryHitVO legacy = isActive(legacyRecord) ? toHit(legacyRecord) : null;
        return legacy == null ? null : new L2SessionSummarySnapshot(legacy, 0L);
    }

    @Override
    @Transactional(transactionManager = "mysqlTransactionManager")
    public L2SessionSummaryWriteResult saveSessionSummaryCas(
            String userId,
            String sessionId,
            String content,
            Double importance,
            String metadataJson,
            long expectedRevision) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("Session summary owner, session and content are required");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
        if (mysqlJdbcTemplate == null) {
            saveMemory(userId, sessionId, SESSION_SUMMARY_MEMORY_TYPE, content, importance, metadataJson);
            L2MemoryHitVO current = latestSessionSummary(userId, sessionId);
            return new L2SessionSummaryWriteResult(true, expectedRevision + 1L, current);
        }

        String owner = userId.trim();
        String session = sessionId.trim();
        mysqlJdbcTemplate.update("""
                INSERT IGNORE INTO ai_memory_slot
                    (owner_user_id, scope_type, scope_key, memory_type, slot_key, revision)
                VALUES (?, 'SESSION', ?, ?, '', 0)
                """, owner, session, SESSION_SUMMARY_MEMORY_TYPE);

        L2MemoryRecord record = newMemoryRecord(
                owner, session, SESSION_SUMMARY_MEMORY_TYPE, content, importance, metadataJson);
        l2MemoryRecordMapper.insert(record);
        int updated = mysqlJdbcTemplate.update("""
                UPDATE ai_memory_slot
                SET current_record_id = ?, revision = revision + 1
                WHERE owner_user_id = ?
                  AND scope_type = 'SESSION'
                  AND scope_key = ?
                  AND memory_type = ?
                  AND slot_key = ''
                  AND revision = ?
                """, record.getId(), owner, session, SESSION_SUMMARY_MEMORY_TYPE, expectedRevision);
        if (updated == 1) {
            return new L2SessionSummaryWriteResult(true, expectedRevision + 1L, toHit(record));
        }

        l2MemoryRecordMapper.deleteById(record.getId());
        L2SessionSummarySnapshot current = latestSessionSummarySnapshot(owner, session);
        return new L2SessionSummaryWriteResult(
                false,
                current == null ? expectedRevision : current.revision(),
                current == null ? null : current.memory());
    }

    @Override
    public List<L2MemoryHitVO> retrieve(String userId, String sessionId, String query, int topK, double threshold) {
        return recall(new L2MemoryRecallQuery(
                userId, sessionId, query, List.of(), topK, threshold,
                configuredCandidateLimit, false)).hits();
    }

    @Override
    public L2MemoryRecallResult recall(L2MemoryRecallQuery request) {
        int maxConfiguredCandidates = Math.max(1, configuredCandidateLimit);
        int requestedLimit = request == null ? maxConfiguredCandidates : request.candidateLimit();
        int safeCandidateLimit = Math.min(requestedLimit <= 0 ? maxConfiguredCandidates : requestedLimit,
                maxConfiguredCandidates);
        if (request == null || !StringUtils.hasText(request.userId()) || !StringUtils.hasText(request.query())) {
            return L2MemoryRecallResult.empty(safeCandidateLimit, "EMPTY", "INVALID_QUERY_SCOPE");
        }

        int safeTopK = request.topK() <= 0 ? 5 : Math.min(request.topK(), 20);
        double safeThreshold = Math.max(0D, Math.min(request.relevanceThreshold(), 1D));
        String owner = request.userId().trim();
        String currentSession = StringUtils.hasText(request.sessionId()) ? request.sessionId().trim() : "";
        List<String> memoryTypes = normalizeMemoryTypes(request.memoryTypes());

        LambdaQueryWrapper<L2MemoryRecord> countWrapper = candidateWrapper(owner, currentSession, memoryTypes);
        Long counted = l2MemoryRecordMapper.selectCount(countWrapper);
        LambdaQueryWrapper<L2MemoryRecord> candidateWrapper = candidateWrapper(owner, currentSession, memoryTypes);
        candidateWrapper.orderByDesc(L2MemoryRecord::getUpdatedTime)
                .orderByDesc(L2MemoryRecord::getId)
                .last("LIMIT " + safeCandidateLimit);
        List<L2MemoryRecord> candidates = l2MemoryRecordMapper.selectList(candidateWrapper);
        if (candidates == null) {
            candidates = List.of();
        }
        long eligibleCount = counted == null ? candidates.size() : counted;
        boolean candidateTruncated = eligibleCount > safeCandidateLimit;
        if (candidates.isEmpty()) {
            return new L2MemoryRecallResult(
                    List.of(), eligibleCount, 0, safeCandidateLimit, candidateTruncated,
                    "EMPTY", "NO_ELIGIBLE_CANDIDATES", List.of(), List.of());
        }

        List<String> queryTokens = tokenize(request.query());
        QueryEmbedding queryEmbedding = request.forceLexicalFallback()
                ? new QueryEmbedding(new float[0], "FORCED_LEXICAL_FALLBACK")
                : embedQueryWithStatus(request.query());
        List<L2MemoryHitVO> scored = new ArrayList<>();
        List<L2MemoryRecallExclusion> exclusions = new ArrayList<>();
        int semanticCandidateCount = 0;

        for (L2MemoryRecord one : candidates) {
            if (one == null || !owner.equals(one.getUserId()) || !isActive(one)) {
                addExclusion(exclusions, one, "SCOPE_OR_STATUS_MISMATCH");
                continue;
            }
            if (SESSION_SUMMARY_MEMORY_TYPE.equals(one.getMemoryType())
                    && (!StringUtils.hasText(currentSession) || !currentSession.equals(one.getSessionId()))) {
                addExclusion(exclusions, one, "SESSION_SCOPE_MISMATCH");
                continue;
            }
            String text = one.getContent();
            if (!StringUtils.hasText(text)) {
                addExclusion(exclusions, one, "EMPTY_CONTENT");
                continue;
            }

            double lexicalScore = lexicalCoverageScore(queryTokens, text);
            Double semanticScore = semanticScoreOrNull(queryEmbedding.vector(), one.getEmbeddingJson());
            if (semanticScore != null) {
                semanticCandidateCount++;
            }
            double embeddingScore = semanticScore == null ? lexicalScore : semanticScore;
            double tfScore = tfBagScore(queryTokens, text);
            double relevanceScore = Math.max(embeddingScore, Math.max(lexicalScore, tfScore));
            if (relevanceScore < safeThreshold) {
                addExclusion(exclusions, one, "BELOW_RELEVANCE_THRESHOLD");
                continue;
            }

            double importanceScore = normalize01(one.getImportance());
            L2MemoryHitVO hit = toHit(one);
            hit.setEmbeddingScore(embeddingScore);
            hit.setRelevanceScore(relevanceScore);
            hit.setImportanceScore(importanceScore);
            hit.setTfScore(tfScore);
            hit.setScore(RELEVANCE_WEIGHT * relevanceScore + IMPORTANCE_WEIGHT * importanceScore);
            hit.setRetrievalMode(semanticScore == null ? "LEXICAL_FALLBACK" : "EMBEDDING");
            scored.add(hit);
        }

        scored.sort(Comparator.comparing(L2MemoryHitVO::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(L2MemoryHitVO::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, L2MemoryHitVO> deduplicated = new LinkedHashMap<>();
        for (L2MemoryHitVO hit : scored) {
            String dedupeKey = memoryDedupeKey(hit);
            if (deduplicated.putIfAbsent(dedupeKey, hit) != null) {
                addExclusion(exclusions, hit.getId(), hit.getMemoryType(), "DUPLICATE");
            }
        }
        List<L2MemoryHitVO> hits = deduplicated.values().stream().limit(safeTopK).toList();
        Set<Long> adoptedIds = hits.stream().map(L2MemoryHitVO::getId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        for (L2MemoryHitVO hit : deduplicated.values()) {
            if (hit.getId() != null && !adoptedIds.contains(hit.getId())) {
                addExclusion(exclusions, hit.getId(), hit.getMemoryType(), "OUTSIDE_TOP_K");
            }
        }

        String retrievalMode;
        String fallbackReason = "";
        if (queryEmbedding.vector().length == 0) {
            retrievalMode = "LEXICAL_FALLBACK";
            fallbackReason = queryEmbedding.fallbackReason();
        } else if (semanticCandidateCount == 0) {
            retrievalMode = "LEXICAL_FALLBACK";
            fallbackReason = "CANDIDATE_EMBEDDING_MISSING";
        } else if (semanticCandidateCount < candidates.size()) {
            retrievalMode = "HYBRID";
            fallbackReason = "PARTIAL_CANDIDATE_EMBEDDING_MISSING";
        } else {
            retrievalMode = "EMBEDDING";
        }
        int auditLimit = Math.max(0, configuredAuditExclusionLimit);
        List<L2MemoryRecallExclusion> auditedExclusions = auditLimit == 0
                ? List.of() : exclusions.stream().limit(auditLimit).toList();
        return new L2MemoryRecallResult(
                hits, eligibleCount, candidates.size(), safeCandidateLimit, candidateTruncated,
                retrievalMode, fallbackReason,
                hits.stream().map(L2MemoryHitVO::getId).filter(java.util.Objects::nonNull).toList(),
                auditedExclusions);
    }

    private LambdaQueryWrapper<L2MemoryRecord> candidateWrapper(
            String owner, String currentSession, List<String> memoryTypes) {
        LambdaQueryWrapper<L2MemoryRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(L2MemoryRecord::getUserId, owner)
                .eq(L2MemoryRecord::getStatus, ACTIVE_STATUS)
                .isNull(L2MemoryRecord::getDeletedAt)
                .and(w -> w.isNull(L2MemoryRecord::getValidUntil)
                        .or().gt(L2MemoryRecord::getValidUntil, LocalDateTime.now()));
        if (!memoryTypes.isEmpty()) {
            wrapper.in(L2MemoryRecord::getMemoryType, memoryTypes);
        }
        if (StringUtils.hasText(currentSession)) {
            wrapper.and(w -> w.ne(L2MemoryRecord::getMemoryType, SESSION_SUMMARY_MEMORY_TYPE)
                    .or(s -> s.eq(L2MemoryRecord::getMemoryType, SESSION_SUMMARY_MEMORY_TYPE)
                            .eq(L2MemoryRecord::getSessionId, currentSession)));
        } else {
            wrapper.ne(L2MemoryRecord::getMemoryType, SESSION_SUMMARY_MEMORY_TYPE);
        }
        return wrapper;
    }

    private List<String> normalizeMemoryTypes(List<String> requestedTypes) {
        if (requestedTypes == null || requestedTypes.isEmpty()) {
            return List.of();
        }
        return requestedTypes.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String memoryDedupeKey(L2MemoryHitVO hit) {
        if (StringUtils.hasText(hit.getSlotKey())) {
            return String.join("|", safeText(hit.getMemoryType()), safeText(hit.getScopeType()),
                    safeText(hit.getScopeKey()), safeText(hit.getSlotKey()));
        }
        return safeText(hit.getMemoryType()) + "|" + safeText(hit.getContent()).replaceAll("\\s+", " ");
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void addExclusion(List<L2MemoryRecallExclusion> exclusions, L2MemoryRecord record, String reason) {
        addExclusion(exclusions, record == null ? null : record.getId(),
                record == null ? "" : record.getMemoryType(), reason);
    }

    private void addExclusion(List<L2MemoryRecallExclusion> exclusions, Long id, String memoryType, String reason) {
        if (exclusions.size() < Math.max(0, configuredAuditExclusionLimit)) {
            exclusions.add(new L2MemoryRecallExclusion(id, memoryType, reason));
        }
    }

    private L2MemoryHitVO toHit(L2MemoryRecord record) {
        if (record == null) {
            return null;
        }
        L2MemoryHitVO hit = new L2MemoryHitVO();
        hit.setId(record.getId());
        hit.setUserId(record.getUserId());
        hit.setSessionId(record.getSessionId());
        hit.setMemoryType(record.getMemoryType());
        hit.setContent(record.getContent());
        hit.setMetadataJson(record.getMetadataJson());
        hit.setSourceEventId(record.getSourceEventId());
        hit.setScopeType(record.getScopeType());
        hit.setScopeKey(record.getScopeKey());
        hit.setSlotKey(record.getSlotKey());
        hit.setRevision(record.getRevision());
        hit.setStatus(record.getStatus());
        hit.setImportanceScore(normalize01(record.getImportance()));
        return hit;
    }

    private SlotState lockSlot(SlotIdentity slot) {
        if (mysqlJdbcTemplate == null) {
            throw new IllegalStateException("MySQL slot storage is unavailable");
        }
        mysqlJdbcTemplate.update("""
                INSERT INTO ai_memory_slot
                    (owner_user_id, scope_type, scope_key, memory_type, slot_key, revision)
                VALUES (?, ?, ?, ?, ?, 0)
                ON DUPLICATE KEY UPDATE id = id
                """, slot.userId(), slot.scopeType(), slot.scopeKey(), slot.memoryType(), slot.slotKey());
        List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList("""
                SELECT id, current_record_id, revision
                FROM ai_memory_slot
                WHERE owner_user_id = ? AND scope_type = ? AND scope_key = ?
                  AND memory_type = ? AND slot_key = ?
                FOR UPDATE
                """, slot.userId(), slot.scopeType(), slot.scopeKey(), slot.memoryType(), slot.slotKey());
        if (rows.size() != 1) {
            throw new IllegalStateException("Memory slot could not be locked");
        }
        Map<String, Object> row = rows.get(0);
        Number slotId = (Number) row.get("id");
        Number currentRecordId = (Number) row.get("current_record_id");
        Number revision = (Number) row.get("revision");
        return new SlotState(slotId.longValue(),
                currentRecordId == null ? null : currentRecordId.longValue(),
                revision == null ? 0L : revision.longValue());
    }

    private L2MemoryRecord findByIdempotencyForUpdate(String userId, String idempotencyKey) {
        List<Long> ids = mysqlJdbcTemplate.query("""
                SELECT id FROM ai_l2_memory_record
                WHERE user_id = ? AND idempotency_key = ?
                LIMIT 1 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong(1), userId, idempotencyKey);
        return ids.isEmpty() ? null : l2MemoryRecordMapper.selectById(ids.get(0));
    }

    private L2MemoryMutationResult replayResult(L2MemoryRecord existing, String payloadHash) {
        if (!payloadHash.equals(existing.getPayloadHash())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "sourceEventId 已被不同的记忆载荷使用");
        }
        return new L2MemoryMutationResult(existing.getId(), "REPLAYED",
                existing.getStatus(), existing.getRevision() == null ? 0L : existing.getRevision());
    }

    private SlotIdentity normalizeWriteCommand(L2MemoryWriteCommand command) {
        if (command == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "记忆写入参数不能为空");
        }
        String content = required(command.content(), "content", 8000);
        return normalizeIdentity(command.userId(), command.sessionId(), command.memoryType(),
                command.sourceEventId(), command.sourceType(), command.assertionStatus(),
                command.scopeType(), command.scopeKey(), command.slotKey(), content,
                command.importance(), command.metadataJson());
    }

    private SlotIdentity normalizeDeleteCommand(L2MemoryDeleteCommand command) {
        if (command == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "记忆删除参数不能为空");
        }
        return normalizeIdentity(command.userId(), "", command.memoryType(), command.sourceEventId(),
                "USER_REQUEST", "CONFIRMED", command.scopeType(), command.scopeKey(), command.slotKey(),
                "", 0D, "{}");
    }

    private SlotIdentity normalizeIdentity(
            String userId, String sessionId, String memoryType, String sourceEventId,
            String sourceType, String assertionStatus, String scopeType, String scopeKey,
            String slotKey, String content, Double importance, String metadataJson) {
        String owner = required(userId, "userId", 64);
        String event = required(sourceEventId, "sourceEventId", 128);
        String type = required(memoryType, "memoryType", 32).toUpperCase(Locale.ROOT);
        String normalizedScopeType = StringUtils.hasText(scopeType)
                ? required(scopeType, "scopeType", 32).toUpperCase(Locale.ROOT) : "USER";
        String normalizedScopeKey = StringUtils.hasText(scopeKey)
                ? required(scopeKey, "scopeKey", 128) : "USER";
        String normalizedSlotKey = required(slotKey, "slotKey", 128).toLowerCase(Locale.ROOT);
        String normalizedSession = StringUtils.hasText(sessionId) ? required(sessionId, "sessionId", 128) : "";
        return new SlotIdentity(owner, normalizedSession, type, content.trim(),
                normalize01(importance == null ? DEFAULT_L2_IMPORTANCE : importance),
                StringUtils.hasText(metadataJson) ? metadataJson.trim() : "{}", event,
                StringUtils.hasText(sourceType) ? sourceType.trim().toUpperCase(Locale.ROOT) : "MODEL_INFERRED",
                StringUtils.hasText(assertionStatus) ? assertionStatus.trim().toUpperCase(Locale.ROOT) : "INFERRED",
                normalizedScopeType, normalizedScopeKey, normalizedSlotKey);
    }

    private String required(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, field + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ResultCode.BAD_REQUEST, field + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private boolean shouldSkipLongTermMemory(SlotIdentity slot) {
        String lower = slot.content().toLowerCase(Locale.ROOT);
        boolean credential = lower.matches(".*(密码|验证码|api[-_ ]?key|access[-_ ]?token|secret|bearer\\s+)[：:= ]*\\S+.*");
        boolean transientNoise = slot.importance() <= 0.15D
                && (lower.contains("临时") || lower.contains("本轮") || lower.contains("一次性")
                || lower.contains("对话噪声") || lower.matches("^(你好|您好|谢谢|收到)[！!。.]?$"));
        if (credential || transientNoise) {
            log.info("L2 memory candidate skipped by deterministic store guard, type={}, reason={}",
                    slot.memoryType(), credential ? "SENSITIVE_CREDENTIAL" : "TRANSIENT_NOISE");
            return true;
        }
        return false;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String idempotencyKey(SlotIdentity slot) {
        return sha256(String.join("|", slot.userId(), slot.sourceEventId(),
                slot.memoryType(), slot.scopeType(), slot.scopeKey(), slot.slotKey()));
    }

    private record SlotState(long slotId, Long currentRecordId, long revision) {
    }

    private record SlotIdentity(
            String userId, String sessionId, String memoryType, String content,
            Double importance, String metadataJson, String sourceEventId,
            String sourceType, String assertionStatus, String scopeType,
            String scopeKey, String slotKey) {
    }

    private L2MemoryRecord newMemoryRecord(
            String userId,
            String sessionId,
            String memoryType,
            String content,
            Double importance,
            String metadataJson) {
        L2MemoryRecord record = new L2MemoryRecord();
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setMemoryType(memoryType);
        record.setContent(content.trim());
        record.setImportance(normalize01(importance == null ? DEFAULT_L2_IMPORTANCE : importance));
        record.setEmbeddingJson(toEmbeddingJson(content.trim()));
        record.setMetadataJson(StringUtils.hasText(metadataJson) ? metadataJson.trim() : "{}");
        initializeLifecycle(record);
        record.setCreatedTime(LocalDateTime.now());
        record.setUpdatedTime(LocalDateTime.now());
        return record;
    }

    private void initializeLifecycle(L2MemoryRecord record) {
        String session = StringUtils.hasText(record.getSessionId()) ? record.getSessionId().trim() : "";
        boolean sessionScoped = SESSION_SUMMARY_MEMORY_TYPE.equals(record.getMemoryType());
        record.setSourceType("MODEL_INFERRED");
        record.setAssertionStatus("INFERRED");
        record.setScopeType(sessionScoped ? "SESSION" : "USER");
        record.setScopeKey(sessionScoped ? session : "USER");
        record.setSlotKey("");
        record.setRevision(0L);
        record.setStatus(ACTIVE_STATUS);
        record.setValidFrom(LocalDateTime.now());
    }

    private boolean isActive(L2MemoryRecord record) {
        if (record == null || !ACTIVE_STATUS.equals(record.getStatus()) || record.getDeletedAt() != null) {
            return false;
        }
        return record.getValidUntil() == null || record.getValidUntil().isAfter(LocalDateTime.now());
    }

    private double lexicalCoverageScore(List<String> queryTokens, String text) {
        if (queryTokens.isEmpty() || !StringUtils.hasText(text)) {
            return 0D;
        }
        String lower = normalizeMemoryLexicon(text);
        long hit = queryTokens.stream().filter(lower::contains).count();
        return hit * 1.0D / queryTokens.size();
    }

    private String toEmbeddingJson(String content) {
        try {
            List<float[]> vectors = embeddingService.embed(List.of(content));
            if (vectors == null || vectors.isEmpty()) {
                return "[]";
            }
            List<Double> values = new ArrayList<>();
            for (float value : vectors.get(0)) {
                values.add((double) value);
            }
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            log.warn("L2 memory embedding failed, fallback to lexical recall. reason={}", ex.getMessage());
            return "[]";
        }
    }

    private QueryEmbedding embedQueryWithStatus(String query) {
        try {
            List<float[]> vectors = embeddingService.embed(List.of(query));
            if (vectors == null || vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length == 0) {
                return new QueryEmbedding(new float[0], "EMPTY_QUERY_EMBEDDING");
            }
            return new QueryEmbedding(vectors.get(0), "");
        } catch (Exception ex) {
            log.warn("L2 query embedding failed, fallback to lexical recall. reason={}", ex.getMessage());
            return new QueryEmbedding(new float[0], "EMBEDDING_UNAVAILABLE");
        }
    }

    private Double semanticScoreOrNull(float[] queryVector, String embeddingJson) {
        if (queryVector == null || queryVector.length == 0 || !StringUtils.hasText(embeddingJson) || "[]".equals(embeddingJson.trim())) {
            return null;
        }
        try {
            List<Double> values = objectMapper.readValue(embeddingJson, new TypeReference<List<Double>>() {});
            if (values == null || values.isEmpty() || values.size() != queryVector.length) {
                return null;
            }
            double dot = 0D;
            double qNorm = 0D;
            double mNorm = 0D;
            for (int i = 0; i < queryVector.length; i++) {
                double q = queryVector[i];
                double m = values.get(i);
                dot += q * m;
                qNorm += q * q;
                mNorm += m * m;
            }
            if (qNorm <= 0D || mNorm <= 0D) {
                return null;
            }
            double cosine = dot / (Math.sqrt(qNorm) * Math.sqrt(mNorm));
            return normalize01((cosine + 1D) / 2D);
        } catch (Exception ex) {
            return null;
        }
    }

    private double tfBagScore(List<String> queryTokens, String text) {
        if (queryTokens.isEmpty() || !StringUtils.hasText(text)) {
            return 0D;
        }
        String lower = normalizeMemoryLexicon(text);
        double total = 0D;
        for (String token : queryTokens) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            int freq = countContains(lower, token);
            total += Math.min(freq, 3);
        }
        return Math.min(total / (queryTokens.size() * 2D), 1.0D);
    }

    private int countContains(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }

    private List<String> tokenize(String q) {
        Set<String> set = new java.util.LinkedHashSet<>();
        Matcher matcher = LEXICAL_TOKEN_PATTERN.matcher(normalizeMemoryLexicon(q));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.codePoints().allMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.HAN)) {
                if (token.length() == 1) {
                    continue;
                }
                if (token.length() == 2) {
                    set.add(token);
                    continue;
                }
                for (int index = 0; index < token.length() - 1; index++) {
                    set.add(token.substring(index, index + 2));
                }
            } else if (token.length() >= 2) {
                set.add(token);
            }
        }
        return new ArrayList<>(set);
    }

    private String normalizeMemoryLexicon(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("偏爱", "关注")
                .replace("喜爱", "关注")
                .replace("喜欢", "关注")
                .replace("偏好", "关注");
    }

    private double normalize01(Double value) {
        if (value == null) {
            return 0D;
        }
        if (value < 0D) {
            return 0D;
        }
        return Math.min(value, 1.0D);
    }

    private record QueryEmbedding(float[] vector, String fallbackReason) {
    }
}
