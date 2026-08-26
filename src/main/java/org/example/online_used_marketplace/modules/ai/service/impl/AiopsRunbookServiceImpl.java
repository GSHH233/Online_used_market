package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.aiops.docker.DockerRestartResult;
import org.example.online_used_marketplace.modules.ai.aiops.docker.DockerRestartRunbook;
import org.example.online_used_marketplace.modules.ai.aiops.demo.AiopsDemoService;
import org.example.online_used_marketplace.modules.ai.aiops.demo.vo.AiopsDemoServiceStateVO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsRunbookExecuteRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeRepairBatchRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStatus;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.service.AiopsRunbookService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeIndexService;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsRunbookActionVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeIndexTaskVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeReindexTriggerVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeRepairBatchResponseVO;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AiopsRunbookServiceImpl implements AiopsRunbookService {

    private static final String REPAIR_KNOWLEDGE_DOC = "REPAIR_KNOWLEDGE_DOC";
    private static final String REPAIR_KNOWLEDGE_BATCH = "REPAIR_KNOWLEDGE_BATCH";
    private static final String ROLLBACK_KNOWLEDGE_REVISION = "ROLLBACK_KNOWLEDGE_REVISION";
    private static final String MARK_EVENT_RESOLVED = "MARK_EVENT_RESOLVED";
    private static final String DOCKER_RESTART_VERIFY_ROLLBACK = "DOCKER_RESTART_VERIFY_ROLLBACK";

    private final KnowledgeIndexService knowledgeIndexService;
    private final AiopsEventService aiopsEventService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private AiopsDemoService aiopsDemoService;
    private DockerRestartRunbook dockerRestartRunbook;

    public AiopsRunbookServiceImpl(KnowledgeIndexService knowledgeIndexService,
                                   AiopsEventService aiopsEventService,
                                   ObjectMapper objectMapper,
                                   @Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.knowledgeIndexService = knowledgeIndexService;
        this.aiopsEventService = aiopsEventService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Autowired(required = false)
    public void setAiopsDemoService(AiopsDemoService aiopsDemoService) {
        this.aiopsDemoService = aiopsDemoService;
    }

    @Autowired(required = false)
    public void setDockerRestartRunbook(DockerRestartRunbook dockerRestartRunbook) {
        this.dockerRestartRunbook = dockerRestartRunbook;
    }

    @Override
    public List<AiopsRunbookActionVO> listRunbooks() {
        List<AiopsRunbookActionVO> out = new ArrayList<>();
        out.add(catalog(REPAIR_KNOWLEDGE_DOC, "修复单个知识文档索引", "LOW",
                Map.of("requiredParams", List.of("docId"), "optionalParams", List.of("forceReindex"))));
        out.add(catalog(REPAIR_KNOWLEDGE_BATCH, "批量修复知识文档索引", "LOW",
                Map.of("optionalParams", List.of("sampleDocs", "perDocLimit", "forceReindex"))));
        out.add(catalog(ROLLBACK_KNOWLEDGE_REVISION, "回滚知识文档版本", "MEDIUM",
                Map.of("requiredParams", List.of("targetDocId"), "approvalRequired", true)));
        out.add(catalog(MARK_EVENT_RESOLVED, "标记 AIOps 事件已解决", "LOW",
                Map.of("requiredParams", List.of("eventId"), "optionalParams", List.of("summary"))));
        out.add(catalog("RESET_DEMO_SERVICE_FAULT", "清除演示服务受控故障", "MEDIUM",
                Map.of("requiredParams", List.of("service"),
                        "approvalRequired", true,
                        "executionBoundary", "redis-backed demo service allowlist")));
        Map<String, Object> dockerMetadata = new LinkedHashMap<>();
        dockerMetadata.put("requiredParams", List.of("targetId"));
        dockerMetadata.put("approvalRequired", true);
        dockerMetadata.put("executionBoundary", "isolated Docker target allowlist");
        dockerMetadata.put("enabled", dockerRestartRunbook != null);
        if (dockerRestartRunbook != null) {
            dockerMetadata.putAll(dockerRestartRunbook.catalogMetadata());
        } else {
            dockerMetadata.put("targetId", "marketplace-demo-web");
        }
        out.add(catalog(DOCKER_RESTART_VERIFY_ROLLBACK,
                "重启隔离容器并验证，失败时回滚", "MEDIUM", dockerMetadata));
        return out;
    }

    @Override
    public AiopsRunbookActionVO execute(AiopsRunbookExecuteRequestDTO request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
        String actionCode = normalizeAction(request.getActionCode());
        Map<String, Object> params = request.getParams() == null ? Map.of() : new LinkedHashMap<>(request.getParams());
        boolean dryRun = !Boolean.FALSE.equals(request.getDryRun());
        String idempotencyKey = buildIdempotencyKey(request, actionCode, params);
        String traceId = UUID.randomUUID().toString();

        long start = System.currentTimeMillis();
        AiopsRunbookActionVO vo = new AiopsRunbookActionVO();
        vo.setActionCode(actionCode);
        vo.setActionName(actionName(actionCode));
        vo.setRiskLevel(actionRiskLevel(actionCode));
        vo.setDryRun(dryRun);
        vo.setExecuted(false);
        vo.setTraceId(traceId);
        vo.setIdempotencyKey(idempotencyKey);
        vo.setParams(params);
        vo.setStatus(dryRun ? "DRY_RUN" : "RUNNING");
        vo.setVerificationStatus("NOT_STARTED");
        validateParams(actionCode, params, request.getEventId());

        if (dryRun) {
            vo.setMessage("dry-run: runbook action is valid and ready to execute");
            vo.setResult(Map.of("planned", true, "actionCode", actionCode, "params", params));
            vo.setMetadata(baseMetadata(request, vo, null));
            vo.setElapsedMs(System.currentTimeMillis() - start);
            return vo;
        }

        Long eventId = resolveEventId(request, actionCode, idempotencyKey, params);
        vo.setEventId(eventId);
        Long duplicateActionId = findDuplicateSuccessAction(eventId, actionCode, idempotencyKey);
        if (duplicateActionId != null) {
            vo.setActionId(duplicateActionId);
            vo.setStatus("SKIPPED");
            vo.setMessage("runbook action already executed with same idempotencyKey");
            vo.setVerificationStatus("IDEMPOTENT_HIT");
            vo.setResult(Map.of("duplicateActionId", duplicateActionId));
            vo.setMetadata(baseMetadata(request, vo, null));
            vo.setElapsedMs(System.currentTimeMillis() - start);
            return vo;
        }

        aiopsEventService.markExecuting(eventId, traceId);
        Long actionId = null;
        try {
            actionId = insertAction(eventId, actionCode, traceId, idempotencyKey, "RUNNING",
                    request.getReason(), false, "NOT_STARTED", baseMetadata(request, vo, null));
            vo.setActionId(actionId);
            RunbookExecutionResult result = executeAction(actionCode, params, request, eventId);
            result = syncSourceEvent(result, params, eventId);
            vo.setExecuted(true);
            vo.setStatus(result.success() ? "SUCCESS" : "FAILED");
            vo.setMessage(result.message());
            vo.setVerificationStatus(result.verificationStatus());
            vo.setResult(result.result());
            if (!MARK_EVENT_RESOLVED.equals(actionCode)) {
                aiopsEventService.markVerifying(eventId);
                if (result.success()) {
                    aiopsEventService.markResolved(eventId, "AUTO_VERIFIED", result.message());
                } else {
                    aiopsEventService.markEscalated(eventId, result.message(), "自动修复验证失败，已转人工处理");
                }
            }
            vo.setMetadata(baseMetadata(request, vo, result.result()));
            vo.setElapsedMs(System.currentTimeMillis() - start);
            updateAction(actionId, vo.getStatus(), true, result.verificationStatus(), result.message(), vo.getMetadata());
            return vo;
        } catch (RuntimeException ex) {
            vo.setStatus("FAILED");
            vo.setMessage(StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName());
            vo.setVerificationStatus("FAILED");
            vo.setResult(Map.of("error", vo.getMessage()));
            vo.setMetadata(baseMetadata(request, vo, vo.getResult()));
            vo.setElapsedMs(System.currentTimeMillis() - start);
            aiopsEventService.markEscalated(eventId, vo.getMessage(), "自动修复执行异常，已转人工处理");
            syncSourceEventSafely(params, eventId, vo.getMessage());
            if (actionId != null) {
                updateAction(actionId, "FAILED", true, "FAILED", vo.getMessage(), vo.getMetadata());
            }
            return vo;
        }
    }

    private void validateParams(String actionCode, Map<String, Object> params, Long eventId) {
        switch (actionCode) {
            case REPAIR_KNOWLEDGE_DOC -> requiredLong(params, "docId");
            case ROLLBACK_KNOWLEDGE_REVISION -> requiredLong(params, "targetDocId");
            case MARK_EVENT_RESOLVED -> {
                if ((eventId == null || eventId <= 0) && longValue(params.get("eventId"), null) == null) {
                    requiredLong(params, "eventId");
                }
            }
            case REPAIR_KNOWLEDGE_BATCH -> {
                integer(params.get("sampleDocs"), 20);
                integer(params.get("perDocLimit"), 200);
            }
            case "RESET_DEMO_SERVICE_FAULT" -> {
                if (!StringUtils.hasText(str(params.get("service"), ""))) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "RESET_DEMO_SERVICE_FAULT 需要 service");
                }
            }
            case DOCKER_RESTART_VERIFY_ROLLBACK -> validateDockerTarget(params);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的runbook动作：" + actionCode);
        }
    }

    private RunbookExecutionResult executeAction(String actionCode,
                                                 Map<String, Object> params,
                                                 AiopsRunbookExecuteRequestDTO request,
                                                 Long eventId) {
        return switch (actionCode) {
            case REPAIR_KNOWLEDGE_DOC -> repairKnowledgeDoc(params);
            case REPAIR_KNOWLEDGE_BATCH -> repairKnowledgeBatch(params);
            case ROLLBACK_KNOWLEDGE_REVISION -> rollbackKnowledgeRevision(params);
            case MARK_EVENT_RESOLVED -> markEventResolved(eventId, params);
            case "RESET_DEMO_SERVICE_FAULT" -> resetDemoServiceFault(params, request);
            case DOCKER_RESTART_VERIFY_ROLLBACK -> restartDockerAndVerify(params);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的runbook动作：" + actionCode);
        };
    }

    private RunbookExecutionResult repairKnowledgeDoc(Map<String, Object> params) {
        Long docId = requiredLong(params, "docId");
        boolean forceReindex = bool(params.get("forceReindex"), false);
        KnowledgeReindexTriggerVO trigger = knowledgeIndexService.repairDoc(docId, forceReindex, false);
        KnowledgeIndexTaskVO task = trigger.getTask();
        boolean success = task != null && !KnowledgeIndexTaskStatus.FAILED.equals(task.getStatus())
                && Boolean.TRUE.equals(task.getConsistent());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", trigger.getTaskId());
        result.put("docId", trigger.getDocId());
        result.put("status", trigger.getStatus() == null ? "" : trigger.getStatus().name());
        result.put("message", trigger.getMessage());
        if (task != null) {
            result.put("consistent", task.getConsistent());
            result.put("dbCount", task.getDbCount());
            result.put("esCount", task.getEsCount());
            result.put("milvusCount", task.getMilvusCount());
            result.put("elapsedMs", task.getElapsedMs());
            result.put("errorMsg", task.getErrorMsg());
        }
        return new RunbookExecutionResult(success,
                success ? "knowledge doc repair finished and verified" : "knowledge doc repair finished but verification failed",
                success ? "PASSED" : "FAILED",
                result);
    }

    private RunbookExecutionResult repairKnowledgeBatch(Map<String, Object> params) {
        KnowledgeRepairBatchRequestDTO dto = new KnowledgeRepairBatchRequestDTO();
        dto.setSampleDocs(integer(params.get("sampleDocs"), 20));
        dto.setPerDocLimit(integer(params.get("perDocLimit"), 200));
        dto.setForceReindex(bool(params.get("forceReindex"), false));
        dto.setAsync(false);
        KnowledgeRepairBatchResponseVO response = knowledgeIndexService.repairBatch(dto);
        long failed = response.getTasks() == null ? 0 : response.getTasks().stream()
                .filter(task -> KnowledgeIndexTaskStatus.FAILED.equals(task.getStatus())
                        || Boolean.FALSE.equals(task.getConsistent()))
                .count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkedDocs", response.getCheckedDocs());
        result.put("submittedTasks", response.getSubmittedTasks());
        result.put("skippedConsistentDocs", response.getSkippedConsistentDocs());
        result.put("failedTasks", failed);
        boolean success = failed == 0;
        return new RunbookExecutionResult(success,
                success ? "knowledge batch repair finished and verified" : "knowledge batch repair has failed tasks",
                success ? "PASSED" : "FAILED",
                result);
    }

    private RunbookExecutionResult rollbackKnowledgeRevision(Map<String, Object> params) {
        Long targetDocId = requiredLong(params, "targetDocId");
        KnowledgeReindexTriggerVO trigger = knowledgeIndexService.rollbackRevision(targetDocId, false);
        KnowledgeIndexTaskVO task = trigger.getTask();
        boolean success = task != null && !KnowledgeIndexTaskStatus.FAILED.equals(task.getStatus())
                && Boolean.TRUE.equals(task.getConsistent());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", trigger.getTaskId());
        result.put("targetDocId", targetDocId);
        result.put("status", trigger.getStatus() == null ? "" : trigger.getStatus().name());
        result.put("message", trigger.getMessage());
        if (task != null) {
            result.put("consistent", task.getConsistent());
            result.put("dbCount", task.getDbCount());
            result.put("esCount", task.getEsCount());
            result.put("milvusCount", task.getMilvusCount());
            result.put("elapsedMs", task.getElapsedMs());
            result.put("errorMsg", task.getErrorMsg());
        }
        return new RunbookExecutionResult(success,
                success ? "knowledge revision rollback finished and verified" : "knowledge revision rollback verification failed",
                success ? "PASSED" : "FAILED",
                result);
    }

    private RunbookExecutionResult markEventResolved(Long eventId, Map<String, Object> params) {
        Long targetEventId = eventId == null ? requiredLong(params, "eventId") : eventId;
        AiopsEventVO event = aiopsEventService.markResolved(targetEventId, "MANUAL_OVERRIDE",
                str(params.get("summary"), "Runbook marked event as resolved"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", event.getId());
        result.put("status", event.getStatus());
        result.put("lastSeenAt", event.getLastSeenAt() == null ? "" : event.getLastSeenAt().toString());
        boolean success = "RESOLVED".equalsIgnoreCase(event.getStatus());
        return new RunbookExecutionResult(success,
                success ? "aiops event marked as resolved" : "aiops event status verification failed",
                success ? "PASSED" : "FAILED",
                result);
    }

    private RunbookExecutionResult resetDemoServiceFault(Map<String, Object> params,
                                                         AiopsRunbookExecuteRequestDTO request) {
        if (aiopsDemoService == null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "RESET_DEMO_SERVICE_FAULT未启用：demo service bean不可用");
        }
        String service = str(params.get("service"), "");
        if (!StringUtils.hasText(service)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "RESET_DEMO_SERVICE_FAULT 需要 service");
        }
        aiopsDemoService.resetFault(service, request.getIdempotencyKey());

        // The write result is deliberately ignored. Verification must read Redis again.
        AiopsDemoServiceStateVO verified = aiopsDemoService.getState(service);
        boolean healthy = Integer.valueOf(1).equals(verified.getHealth())
                && verified.getErrorRatio() != null
                && Double.compare(0.0D, verified.getErrorRatio()) == 0
                && Boolean.FALSE.equals(verified.getFaulted());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", verified.getService());
        result.put("health", verified.getHealth());
        result.put("errorRatio", verified.getErrorRatio());
        result.put("faulted", verified.getFaulted());
        result.put("traceId", verified.getTraceId());
        result.put("verificationRead", true);
        return new RunbookExecutionResult(healthy,
                healthy ? "demo service fault reset and independently verified"
                        : "demo service reset write completed but health verification failed",
                healthy ? "PASSED" : "FAILED",
                result);
    }

    private void validateDockerTarget(Map<String, Object> params) {
        if (params.containsKey("containerName") || params.containsKey("rollbackImage")
                || params.containsKey("command") || params.containsKey("healthUrl")) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "Docker Runbook 只接受逻辑 targetId，不接受基础设施参数或命令");
        }
        String targetId = str(params.get("targetId"), "");
        if (!StringUtils.hasText(targetId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "DOCKER_RESTART_VERIFY_ROLLBACK 需要 targetId");
        }
        if (dockerRestartRunbook == null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Docker Runbook 默认关闭，需通过环境变量和 SSH 公钥显式启用");
        }
        if (!dockerRestartRunbook.targetId().equals(targetId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "Docker targetId 不在白名单中");
        }
    }

    private RunbookExecutionResult restartDockerAndVerify(Map<String, Object> params) {
        validateDockerTarget(params);
        DockerRestartResult execution = dockerRestartRunbook.execute(str(params.get("targetId"), ""));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetId", dockerRestartRunbook.targetId());
        result.put("outcome", execution.outcome().name());
        result.put("rollbackAttempted", execution.rollbackAttempted());
        result.put("rollbackSuccessful", execution.rollbackSuccessful());
        result.put("stages", execution.stages().stream().map(stage -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stage", stage.stage().name());
            item.put("status", stage.status().name());
            item.put("message", stage.message());
            item.put("elapsedMs", stage.elapsedMs());
            item.put("details", stage.details());
            return item;
        }).toList());
        String verificationStatus = switch (execution.outcome()) {
            case HEALTHY_AFTER_RESTART -> "PASSED";
            case HEALTHY_AFTER_ROLLBACK -> "ROLLED_BACK";
            default -> "FAILED";
        };
        return new RunbookExecutionResult(execution.successful(), execution.message(),
                verificationStatus, result);
    }

    private RunbookExecutionResult syncSourceEvent(RunbookExecutionResult result,
                                                   Map<String, Object> params,
                                                   Long operationEventId) {
        Long sourceEventId = longValue(params.get("__sourceEventId"), null);
        if (sourceEventId == null) {
            return result;
        }
        if (sourceEventId.equals(operationEventId)) {
            throw new BusinessException(ResultCode.CONFLICT, "sourceEventId不能指向当前受控操作事件");
        }
        AiopsEventVO source = aiopsEventService.get(sourceEventId);
        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("sourceEventId", sourceEventId);
        sync.put("beforeStatus", source.getStatus());
        if (List.of("RESOLVED", "ESCALATED", "REJECTED").contains(str(source.getStatus(), "").toUpperCase(Locale.ROOT))) {
            sync.put("updated", false);
            sync.put("skippedTerminal", true);
            sync.put("afterStatus", source.getStatus());
        } else {
            AiopsEventVO updated = result.success()
                    ? aiopsEventService.markResolved(sourceEventId, "AUTO_VERIFIED", result.message())
                    : aiopsEventService.markEscalated(sourceEventId, result.message(), "受控修复或独立验证失败，已转人工处理");
            sync.put("updated", true);
            sync.put("skippedTerminal", false);
            sync.put("afterStatus", updated.getStatus());
        }
        Map<String, Object> enriched = new LinkedHashMap<>(result.result() == null ? Map.of() : result.result());
        enriched.put("sourceEventSync", sync);
        return new RunbookExecutionResult(result.success(), result.message(), result.verificationStatus(), enriched);
    }

    private void syncSourceEventSafely(Map<String, Object> params, Long operationEventId, String message) {
        try {
            syncSourceEvent(new RunbookExecutionResult(false, message, "FAILED", Map.of()), params, operationEventId);
        } catch (RuntimeException ignored) {
            // Keep the original runbook failure visible. Invalid or terminal
            // source events must never be overwritten as a side effect.
        }
    }

    private AiopsRunbookActionVO catalog(String actionCode, String name, String risk, Map<String, Object> metadata) {
        AiopsRunbookActionVO vo = new AiopsRunbookActionVO();
        vo.setActionCode(actionCode);
        vo.setActionName(name);
        vo.setRiskLevel(risk);
        vo.setDryRun(true);
        vo.setExecuted(false);
        vo.setStatus("AVAILABLE");
        vo.setVerificationStatus("NOT_STARTED");
        vo.setMessage("MEDIUM".equalsIgnoreCase(risk)
                ? "white-listed runbook action; controlled approval required for execution"
                : "white-listed low-risk runbook action");
        vo.setMetadata(new LinkedHashMap<>(metadata));
        return vo;
    }

    private Long resolveEventId(AiopsRunbookExecuteRequestDTO request, String actionCode, String idempotencyKey, Map<String, Object> params) {
        if (request.getEventId() != null && request.getEventId() > 0) {
            return request.getEventId();
        }
        Long eventId = longValue(params.get("eventId"), null);
        if (eventId != null && eventId > 0) {
            return eventId;
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "真实执行必须关联已审批的eventId");
    }

    private Long insertAction(Long eventId, String actionCode, String traceId, String idempotencyKey,
                              String status, String suggestion, boolean executed, String verificationStatus,
                              Map<String, Object> metadata) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO aiops_action(event_id,action_type,trace_id,idempotency_key,status,verification_status,
                                         suggestion,executed,started_at,metadata_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?::jsonb)
                RETURNING id
                """, Long.class, eventId, actionCode, traceId, idempotencyKey, status, verificationStatus,
                str(suggestion, ""), executed, writeJson(metadata));
    }

    private void updateAction(Long actionId, String status, boolean executed, String verificationStatus,
                              String suggestion, Map<String, Object> metadata) {
        jdbcTemplate.update("""
                UPDATE aiops_action
                SET status=?, executed=?, verification_status=?, suggestion=?, metadata_json=?::jsonb,
                    finished_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, status, executed, verificationStatus, str(suggestion, ""), writeJson(metadata), actionId);
    }

    private Long findDuplicateSuccessAction(Long eventId, String actionCode, String idempotencyKey) {
        List<Long> rows = jdbcTemplate.query("""
                SELECT id
                FROM aiops_action
                WHERE event_id=?
                  AND action_type=?
                  AND idempotency_key=?
                  AND status IN ('SUCCESS', 'EXECUTED')
                ORDER BY id DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getLong("id"), eventId, actionCode, idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> baseMetadata(AiopsRunbookExecuteRequestDTO request,
                                             AiopsRunbookActionVO vo,
                                             Map<String, Object> result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runbook", true);
        metadata.put("actionCode", vo.getActionCode());
        metadata.put("riskLevel", vo.getRiskLevel());
        metadata.put("dryRun", vo.getDryRun());
        metadata.put("executed", vo.getExecuted());
        metadata.put("idempotencyKey", vo.getIdempotencyKey());
        metadata.put("traceId", vo.getTraceId());
        metadata.put("operatorId", currentOperatorId());
        metadata.put("verificationStatus", vo.getVerificationStatus());
        metadata.put("reason", str(request.getReason(), ""));
        metadata.put("params", vo.getParams());
        if (result != null) {
            metadata.put("result", result);
        }
        return metadata;
    }

    private Long currentOperatorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getUserId();
        }
        return null;
    }

    private String normalizeAction(String actionCode) {
        if (!StringUtils.hasText(actionCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "actionCode不能为空");
        }
        String normalized = actionCode.trim().toUpperCase(Locale.ROOT);
        if (!List.of(REPAIR_KNOWLEDGE_DOC, REPAIR_KNOWLEDGE_BATCH, ROLLBACK_KNOWLEDGE_REVISION,
                MARK_EVENT_RESOLVED, "RESET_DEMO_SERVICE_FAULT", DOCKER_RESTART_VERIFY_ROLLBACK)
                .contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的runbook动作：" + normalized);
        }
        return normalized;
    }

    private String actionName(String actionCode) {
        return switch (actionCode) {
            case REPAIR_KNOWLEDGE_DOC -> "修复单个知识文档索引";
            case REPAIR_KNOWLEDGE_BATCH -> "批量修复知识文档索引";
            case ROLLBACK_KNOWLEDGE_REVISION -> "回滚知识文档版本";
            case MARK_EVENT_RESOLVED -> "标记 AIOps 事件已解决";
            case "RESET_DEMO_SERVICE_FAULT" -> "清除演示服务故障";
            case DOCKER_RESTART_VERIFY_ROLLBACK -> "重启隔离容器并验证，失败时回滚";
            default -> actionCode;
        };
    }

    private String actionRiskLevel(String actionCode) {
        return ROLLBACK_KNOWLEDGE_REVISION.equals(actionCode)
                || "RESET_DEMO_SERVICE_FAULT".equals(actionCode)
                || DOCKER_RESTART_VERIFY_ROLLBACK.equals(actionCode) ? "MEDIUM" : "LOW";
    }

    private String buildIdempotencyKey(AiopsRunbookExecuteRequestDTO request, String actionCode, Map<String, Object> params) {
        if (StringUtils.hasText(request.getIdempotencyKey())) {
            return request.getIdempotencyKey().trim();
        }
        return actionCode + "-" + Integer.toHexString(Objects.hash(actionCode, params, request.getEventId(), request.getReason()));
    }

    private Long requiredLong(Map<String, Object> params, String key) {
        Object value = params.get(key);
        Long out = longValue(value, null);
        if (out == null || out <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, key + "不能为空或不合法");
        }
        return out;
    }

    private Long longValue(Object value, Long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }

    private Integer integer(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    private String str(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : fallback;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private record RunbookExecutionResult(
            boolean success,
            String message,
            String verificationStatus,
            Map<String, Object> result
    ) {
    }
}
