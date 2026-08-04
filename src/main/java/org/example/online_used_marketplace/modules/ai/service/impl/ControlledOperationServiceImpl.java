package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventCreateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsEventUpdateDTO;
import org.example.online_used_marketplace.modules.ai.dto.AiopsRunbookExecuteRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ControlledOperationPlanRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.service.AiopsRunbookService;
import org.example.online_used_marketplace.modules.ai.service.ControlledOperationService;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsRunbookActionVO;
import org.example.online_used_marketplace.modules.ai.vo.ControlledOperationVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ControlledOperationServiceImpl implements ControlledOperationService {

    private static final String PLAN_SOURCE = "controlled_operation";
    private final AiopsEventService aiopsEventService;
    private final AiopsRunbookService aiopsRunbookService;
    private final ObjectMapper objectMapper;

    @Override
    public ControlledOperationVO createPlan(ControlledOperationPlanRequestDTO request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "操作计划不能为空");
        }
        String actionCode = normalizeAction(request.getActionCode());
        Map<String, Object> params = request.getParams() == null ? Map.of() : new LinkedHashMap<>(request.getParams());
        validateParams(actionCode, params);
        String traceId = StringUtils.hasText(request.getTraceId()) ? request.getTraceId().trim() : UUID.randomUUID().toString();
        String riskLevel = riskLevel(actionCode);
        String idempotencyKey = "operation-" + UUID.randomUUID();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("operationPlan", true);
        metadata.put("actionCode", actionCode);
        metadata.put("riskLevel", riskLevel);
        metadata.put("params", params);
        metadata.put("reason", safe(request.getReason(), 1000));
        metadata.put("idempotencyKey", idempotencyKey);
        metadata.put("traceId", traceId);

        AiopsEventCreateDTO eventRequest = new AiopsEventCreateDTO();
        eventRequest.setEventKey("operation-" + UUID.randomUUID());
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
        execute.setParams(map(plan.get("params")));
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
        out.setParams(map(plan.get("params")));
        out.setApprovedBy(event.getApprovedBy());
        out.setApprovedAt(event.getApprovedAt());
        out.setManualRequired(event.getManualRequired());
        out.setEscalationReason(event.getEscalationReason());
        return out;
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
        if (!List.of("REPAIR_KNOWLEDGE_DOC", "REPAIR_KNOWLEDGE_BATCH", "MARK_EVENT_RESOLVED").contains(action)) {
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
}
