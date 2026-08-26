package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.aiops.docker.DockerRestartRunbook;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventCreateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventUpdateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsRunbookExecuteRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ControlledOperationPlanRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.service.AiopsRunbookService;
import org.example.online_used_marketplace.modules.ai.service.ControlledOperationService;
import org.example.online_used_marketplace.modules.ai.vo.AiopsActionVO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsRunbookActionVO;
import org.example.online_used_marketplace.modules.ai.vo.ControlledOperationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class ControlledOperationServiceImpl implements ControlledOperationService {

    private static final String PLAN_SOURCE = "controlled_operation";
    private static final List<String> TERMINAL_SOURCE_STATUSES = List.of("RESOLVED", "ESCALATED", "REJECTED");
    private final AiopsEventService aiopsEventService;
    private final AiopsRunbookService aiopsRunbookService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private DockerRestartRunbook dockerRestartRunbook;

    @Autowired
    public ControlledOperationServiceImpl(AiopsEventService aiopsEventService,
                                          AiopsRunbookService aiopsRunbookService,
                                          ObjectMapper objectMapper,
                                          @Qualifier("memoryGraphJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.aiopsEventService = aiopsEventService;
        this.aiopsRunbookService = aiopsRunbookService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Kept for focused unit tests that do not need the operation lookup query. */
    public ControlledOperationServiceImpl(AiopsEventService aiopsEventService,
                                          AiopsRunbookService aiopsRunbookService,
                                          ObjectMapper objectMapper) {
        this(aiopsEventService, aiopsRunbookService, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDockerRestartRunbook(DockerRestartRunbook dockerRestartRunbook) {
        this.dockerRestartRunbook = dockerRestartRunbook;
    }

    @Override
    public ControlledOperationVO createPlan(ControlledOperationPlanRequestDTO request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "操作计划不能为空");
        }
        String actionCode = normalizeAction(request.getActionCode());
        Map<String, Object> params = request.getParams() == null ? Map.of() : new LinkedHashMap<>(request.getParams());
        validateParams(actionCode, params);
        Long sourceEventId = normalizeSourceEventId(request.getSourceEventId());
        if (sourceEventId != null) {
            validateSourceEvent(sourceEventId);
        }
        String traceId = StringUtils.hasText(request.getTraceId()) ? request.getTraceId().trim() : UUID.randomUUID().toString();
        String riskLevel = riskLevel(actionCode);
        String diagnosisHash = resolveDiagnosisHash(request, actionCode, params);
        String eventKey = sourceEventId == null
                ? "operation-" + UUID.randomUUID()
                : stableEventKey(sourceEventId, actionCode, diagnosisHash);
        String idempotencyKey = sourceEventId == null
                ? "operation-" + UUID.randomUUID()
                : stableIdempotencyKey(sourceEventId, actionCode, diagnosisHash);

        if (sourceEventId != null) {
            AiopsEventVO existing = findExistingPlan(eventKey);
            if (existing != null) {
                return toOperation(existing);
            }
            AiopsEventVO source = aiopsEventService.get(sourceEventId);
            if (TERMINAL_SOURCE_STATUSES.contains(normalizeStatus(source.getStatus()))) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "source event已经是终态，不能创建新的受控操作计划：" + source.getStatus());
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("operationPlan", true);
        metadata.put("actionCode", actionCode);
        metadata.put("riskLevel", riskLevel);
        metadata.put("params", params);
        metadata.put("reason", safe(request.getReason(), 1000));
        metadata.put("idempotencyKey", idempotencyKey);
        metadata.put("traceId", traceId);
        metadata.put("diagnosisHash", diagnosisHash);
        if (sourceEventId != null) {
            metadata.put("sourceEventId", sourceEventId);
        }

        AiopsEventCreateDTO eventRequest = new AiopsEventCreateDTO();
        eventRequest.setEventKey(eventKey);
        eventRequest.setTraceId(traceId);
        eventRequest.setSeverity("MEDIUM".equals(riskLevel) ? "WARNING" : "INFO");
        eventRequest.setSource(PLAN_SOURCE);
        eventRequest.setTitle("受控操作计划：" + actionCode);
        eventRequest.setSummary("等待审批的 " + riskLevel + " 风险操作计划");
        eventRequest.setSuggestion(safe(request.getReason(), 1000));
        eventRequest.setMetadata(metadata);
        AiopsEventVO event = aiopsEventService.createOrUpdate(eventRequest);

        AiopsEventUpdateDTO update = new AiopsEventUpdateDTO();
        update.setStatus("PENDING_APPROVAL");
        aiopsEventService.update(event.getId(), update);
        return toOperation(aiopsEventService.get(event.getId()));
    }

    @Override
    public List<ControlledOperationVO> list(String status, Integer limit) {
        int normalizedLimit = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        int fetchLimit = Math.min(100, Math.max(normalizedLimit, normalizedLimit * 5));
        return aiopsEventService.list(status, null, fetchLimit).stream()
                .filter(event -> PLAN_SOURCE.equals(event.getSource()))
                .limit(normalizedLimit)
                .map(this::toOperation)
                .toList();
    }

    @Override
    public ControlledOperationVO get(Long operationId) {
        return toOperation(requireOperation(operationId));
    }

    @Override
    public ControlledOperationVO approve(Long operationId, Long operatorId, String reason) {
        return toOperation(aiopsEventService.approve(requireOperation(operationId).getId(), operatorId, reason));
    }

    @Override
    public ControlledOperationVO reject(Long operationId, Long operatorId, String reason) {
        return toOperation(aiopsEventService.reject(requireOperation(operationId).getId(), operatorId, reason));
    }

    @Override
    public ControlledOperationVO execute(Long operationId) {
        AiopsEventVO event = requireOperation(operationId);
        Map<String, Object> plan = planOf(event);
        boolean idempotentReplay = "RESOLVED".equals(event.getStatus())
                && hasSuccessfulAction(event, plan);
        if (!"APPROVED".equals(event.getStatus()) && !idempotentReplay) {
            throw new BusinessException(ResultCode.CONFLICT, "操作计划尚未审批，当前状态：" + event.getStatus());
        }
        String risk = str(plan.get("riskLevel"));
        if ("HIGH".equals(risk)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "HIGH 风险操作只能转人工处理，不支持自动执行");
        }

        AiopsRunbookExecuteRequestDTO execute = new AiopsRunbookExecuteRequestDTO();
        execute.setActionCode(str(plan.get("actionCode")));
        execute.setEventId(event.getId());
        execute.setIdempotencyKey(str(plan.get("idempotencyKey")));
        execute.setDryRun(false);
        execute.setReason(str(plan.get("reason")));
        Map<String, Object> executionParams = new LinkedHashMap<>(map(plan.get("params")));
        Long sourceEventId = longValue(plan.get("sourceEventId"));
        if (sourceEventId != null) {
            executionParams.put("__sourceEventId", sourceEventId);
        }
        execute.setParams(executionParams);
        AiopsRunbookActionVO action = aiopsRunbookService.execute(execute);
        ControlledOperationVO out = toOperation(aiopsEventService.get(event.getId()));
        out.setExecution(action);
        return out;
    }

    private boolean hasSuccessfulAction(AiopsEventVO event, Map<String, Object> plan) {
        if (event.getActions() == null || event.getActions().isEmpty()) {
            return false;
        }
        String actionCode = str(plan.get("actionCode"));
        String idempotencyKey = str(plan.get("idempotencyKey"));
        return event.getActions().stream().anyMatch(action ->
                actionCode.equalsIgnoreCase(str(action.getActionType()))
                        && idempotencyKey.equals(action.getIdempotencyKey())
                        && List.of("SUCCESS", "EXECUTED").contains(action.getStatus()));
    }

    private AiopsEventVO requireOperation(Long operationId) {
        AiopsEventVO event = aiopsEventService.get(operationId);
        if (!PLAN_SOURCE.equals(event.getSource())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "受控操作计划不存在");
        }
        return event;
    }

    private ControlledOperationVO toOperation(AiopsEventVO event) {
        Map<String, Object> plan = planOf(event);
        ControlledOperationVO out = new ControlledOperationVO();
        out.setOperationId(event.getId());
        out.setTraceId(event.getTraceId());
        out.setActionCode(str(plan.get("actionCode")));
        out.setRiskLevel(str(plan.get("riskLevel")));
        out.setStatus(event.getStatus());
        out.setReason(str(plan.get("reason")));
        out.setIdempotencyKey(str(plan.get("idempotencyKey")));
        out.setSourceEventId(longValue(plan.get("sourceEventId")));
        out.setDiagnosisHash(str(plan.get("diagnosisHash")));
        out.setParams(map(plan.get("params")));
        out.setApprovedBy(event.getApprovedBy());
        out.setApprovedAt(event.getApprovedAt());
        out.setManualRequired(event.getManualRequired());
        out.setEscalationReason(event.getEscalationReason());
        out.setExecution(toExecution(event, plan));
        return out;
    }

    private AiopsRunbookActionVO toExecution(AiopsEventVO event, Map<String, Object> plan) {
        if (event.getActions() == null || event.getActions().isEmpty()) {
            return null;
        }
        String actionCode = str(plan.get("actionCode"));
        String idempotencyKey = str(plan.get("idempotencyKey"));
        AiopsActionVO latest = null;
        for (AiopsActionVO candidate : event.getActions()) {
            boolean matchingAction = actionCode.equalsIgnoreCase(candidate.getActionType());
            boolean matchingKey = !StringUtils.hasText(idempotencyKey)
                    || idempotencyKey.equals(candidate.getIdempotencyKey());
            if (matchingAction && matchingKey
                    && (latest == null || (candidate.getId() != null
                    && (latest.getId() == null || candidate.getId() > latest.getId())))) {
                latest = candidate;
            }
        }
        if (latest == null) {
            return null;
        }
        Map<String, Object> metadata = mapJson(latest.getMetadataJson());
        AiopsRunbookActionVO out = new AiopsRunbookActionVO();
        out.setActionCode(latest.getActionType());
        out.setActionName(actionName(latest.getActionType()));
        out.setRiskLevel(str(plan.get("riskLevel")));
        out.setDryRun(booleanValue(metadata.get("dryRun")));
        out.setExecuted(latest.getExecuted());
        out.setEventId(event.getId());
        out.setActionId(latest.getId());
        out.setTraceId(latest.getTraceId());
        out.setIdempotencyKey(latest.getIdempotencyKey());
        out.setStatus(latest.getStatus());
        out.setMessage(latest.getSuggestion());
        out.setVerificationStatus(latest.getVerificationStatus());
        out.setParams(map(metadata.get("params")).isEmpty() ? map(plan.get("params")) : map(metadata.get("params")));
        out.setResult(map(metadata.get("result")));
        out.setMetadata(metadata);
        if (latest.getStartedAt() != null && latest.getFinishedAt() != null) {
            out.setElapsedMs(Math.max(0L, Duration.between(latest.getStartedAt(), latest.getFinishedAt()).toMillis()));
        }
        return out;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.parseBoolean(String.valueOf(value));
    }

    private String actionName(String actionCode) {
        return switch (actionCode) {
            case "REPAIR_KNOWLEDGE_DOC" -> "修复单个知识文档索引";
            case "REPAIR_KNOWLEDGE_BATCH" -> "批量修复知识文档索引";
            case "ROLLBACK_KNOWLEDGE_REVISION" -> "回滚知识文档版本";
            case "MARK_EVENT_RESOLVED" -> "标记 AIOps 事件已解决";
            case "RESET_DEMO_SERVICE_FAULT" -> "清除演示服务故障";
            case "DOCKER_RESTART_VERIFY_ROLLBACK" -> "重启隔离容器并验证，失败时回滚";
            default -> actionCode;
        };
    }

    private Map<String, Object> planOf(AiopsEventVO event) {
        Map<String, Object> metadata = mapJson(event.getMetadataJson());
        if (!Boolean.TRUE.equals(metadata.get("operationPlan"))) {
            throw new BusinessException(ResultCode.NOT_FOUND, "受控操作计划快照不存在");
        }
        return metadata;
    }

    private String normalizeAction(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "actionCode不能为空");
        }
        String action = raw.trim().toUpperCase(Locale.ROOT);
        if (!List.of("REPAIR_KNOWLEDGE_DOC", "REPAIR_KNOWLEDGE_BATCH", "ROLLBACK_KNOWLEDGE_REVISION",
                "MARK_EVENT_RESOLVED", "RESET_DEMO_SERVICE_FAULT", "DOCKER_RESTART_VERIFY_ROLLBACK")
                .contains(action)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的受控动作：" + action);
        }
        return action;
    }

    private String riskLevel(String actionCode) {
        return "REPAIR_KNOWLEDGE_BATCH".equals(actionCode) ? "HIGH" : "MEDIUM";
    }

    private void validateParams(String actionCode, Map<String, Object> params) {
        if ("REPAIR_KNOWLEDGE_DOC".equals(actionCode)) {
            Object docId = params.get("docId");
            if (!(docId instanceof Number) && !(docId instanceof String && StringUtils.hasText((String) docId))) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "REPAIR_KNOWLEDGE_DOC 需要 docId");
            }
        }
        if ("ROLLBACK_KNOWLEDGE_REVISION".equals(actionCode)) {
            Object targetDocId = params.get("targetDocId");
            if (!(targetDocId instanceof Number) && !(targetDocId instanceof String && StringUtils.hasText((String) targetDocId))) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "ROLLBACK_KNOWLEDGE_REVISION 需要 targetDocId");
            }
        }
        if ("RESET_DEMO_SERVICE_FAULT".equals(actionCode)) {
            Object service = params.get("service");
            if (!(service instanceof String value) || !StringUtils.hasText(value)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "RESET_DEMO_SERVICE_FAULT 需要 service");
            }
        }
        if ("DOCKER_RESTART_VERIFY_ROLLBACK".equals(actionCode)) {
            if (params.containsKey("containerName") || params.containsKey("rollbackImage")
                    || params.containsKey("command") || params.containsKey("healthUrl")) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "Docker Runbook 只接受逻辑 targetId");
            }
            Object targetId = params.get("targetId");
            if (!(targetId instanceof String value) || !StringUtils.hasText(value)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "DOCKER_RESTART_VERIFY_ROLLBACK 需要 targetId");
            }
            if (dockerRestartRunbook == null) {
                throw new BusinessException(ResultCode.CONFLICT, "Docker Runbook 未启用");
            }
            if (!dockerRestartRunbook.targetId().equals(value.trim())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "Docker targetId 不在白名单中");
            }
        }
    }

    private Map<String, Object> mapJson(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            raw.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        return Map.of();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String out = value.trim();
        return out.length() <= max ? out : out.substring(0, max);
    }

    private Long normalizeSourceEventId(Long sourceEventId) {
        if (sourceEventId == null) {
            return null;
        }
        if (sourceEventId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "sourceEventId不合法");
        }
        return sourceEventId;
    }

    private void validateSourceEvent(Long sourceEventId) {
        AiopsEventVO source = aiopsEventService.get(sourceEventId);
        if (PLAN_SOURCE.equals(source.getSource())) {
            throw new BusinessException(ResultCode.CONFLICT, "不能将受控操作计划关联为自身source event");
        }
    }

    private AiopsEventVO findExistingPlan(String eventKey) {
        if (jdbcTemplate != null) {
            List<Long> ids = jdbcTemplate.query("SELECT id FROM aiops_event WHERE event_key=? LIMIT 1",
                    (rs, rowNum) -> rs.getLong("id"), eventKey);
            if (!ids.isEmpty()) {
                return aiopsEventService.get(ids.get(0));
            }
        }
        return aiopsEventService.list(null, null, 100).stream()
                .filter(event -> PLAN_SOURCE.equals(event.getSource()) && eventKey.equals(event.getEventKey()))
                .findFirst()
                .orElse(null);
    }

    private String resolveDiagnosisHash(ControlledOperationPlanRequestDTO request,
                                        String actionCode,
                                        Map<String, Object> params) {
        if (StringUtils.hasText(request.getDiagnosisHash())) {
            return safe(request.getDiagnosisHash(), 96);
        }
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("actionCode", actionCode);
        canonical.put("params", new TreeMap<>(params));
        canonical.put("reason", safe(request.getReason(), 1000));
        try {
            return sha256(objectMapper.writeValueAsString(canonical));
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "diagnosisHash计算失败");
        }
    }

    private String stableEventKey(Long sourceEventId, String actionCode, String diagnosisHash) {
        return "operation-source-" + sourceEventId + "-" + actionCode + "-" + diagnosisHash;
    }

    private String stableIdempotencyKey(Long sourceEventId, String actionCode, String diagnosisHash) {
        return "operation-source-" + sourceEventId + "-" + actionCode + "-" + diagnosisHash;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                out.append(String.format(Locale.ROOT, "%02x", item));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
    }
}
