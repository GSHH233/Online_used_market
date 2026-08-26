package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.modules.ai.aiops.docker.DockerRestartRunbook;
import org.example.online_used_marketplace.modules.ai.dto.ControlledOperationPlanRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.service.AiopsRunbookService;
import org.example.online_used_marketplace.modules.ai.vo.AiopsActionVO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;
import org.example.online_used_marketplace.modules.ai.vo.ControlledOperationVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlledOperationServiceImplTest {

    @Test
    void operationDetailRestoresPersistedRunbookAudit() throws Exception {
        AiopsEventService eventService = mock(AiopsEventService.class);
        AiopsRunbookService runbookService = mock(AiopsRunbookService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AiopsEventVO event = new AiopsEventVO();
        event.setId(78L);
        event.setSource("controlled_operation");
        event.setTraceId("lifecycle-trace-78");
        event.setStatus("RESOLVED");
        event.setMetadataJson(objectMapper.writeValueAsString(Map.of(
                "operationPlan", true,
                "actionCode", "ROLLBACK_KNOWLEDGE_REVISION",
                "riskLevel", "MEDIUM",
                "idempotencyKey", "operation-78",
                "params", Map.of("targetDocId", 86))));
        AiopsActionVO action = new AiopsActionVO();
        action.setId(701L);
        action.setEventId(78L);
        action.setActionType("ROLLBACK_KNOWLEDGE_REVISION");
        action.setTraceId("lifecycle-trace-78");
        action.setIdempotencyKey("operation-78");
        action.setStatus("SUCCESS");
        action.setVerificationStatus("PASSED");
        action.setExecuted(true);
        action.setStartedAt(LocalDateTime.now().minusSeconds(1));
        action.setFinishedAt(LocalDateTime.now());
        action.setMetadataJson(objectMapper.writeValueAsString(Map.of(
                "dryRun", false,
                "params", Map.of("targetDocId", 86),
                "result", Map.of("consistent", true))));
        event.setActions(List.of(action));
        when(eventService.get(78L)).thenReturn(event);

        ControlledOperationServiceImpl service = new ControlledOperationServiceImpl(
                eventService, runbookService, objectMapper);
        ControlledOperationVO operation = service.get(78L);

        assertNotNull(operation.getExecution());
        assertEquals(701L, operation.getExecution().getActionId());
        assertEquals("PASSED", operation.getExecution().getVerificationStatus());
        assertEquals("lifecycle-trace-78", operation.getExecution().getTraceId());
        assertTrue(Boolean.TRUE.equals(operation.getExecution().getExecuted()));
        assertEquals(86, operation.getExecution().getParams().get("targetDocId"));
    }

    @Test
    void sourceEventDiagnosisAndActionCreateStableIdempotentPlan() throws Exception {
        AiopsEventService eventService = mock(AiopsEventService.class);
        AiopsRunbookService runbookService = mock(AiopsRunbookService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AiopsEventVO source = new AiopsEventVO();
        source.setId(42L);
        source.setSource("alertmanager");
        source.setStatus("OPEN");
        AiopsEventVO operationEvent = operationEvent(objectMapper, 100L, 42L);
        when(eventService.get(42L)).thenReturn(source);
        when(eventService.list(null, null, 100)).thenReturn(List.of(), List.of(operationEvent));
        when(eventService.createOrUpdate(org.mockito.ArgumentMatchers.any())).thenReturn(operationEvent);
        when(eventService.get(100L)).thenReturn(operationEvent);

        ControlledOperationServiceImpl service = new ControlledOperationServiceImpl(
                eventService, runbookService, objectMapper);
        ControlledOperationPlanRequestDTO request = new ControlledOperationPlanRequestDTO();
        request.setActionCode("RESET_DEMO_SERVICE_FAULT");
        request.setSourceEventId(42L);
        request.setDiagnosisHash("diagnosis-1");
        request.setParams(Map.of("service", "marketplace-demo-worker"));

        ControlledOperationVO first = service.createPlan(request);
        ControlledOperationVO second = service.createPlan(request);

        assertEquals(100L, first.getOperationId());
        assertEquals(first.getIdempotencyKey(), second.getIdempotencyKey());
        assertEquals(42L, second.getSourceEventId());
        verify(eventService, times(1)).createOrUpdate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sourceEventCannotBeOperationOrTerminal() {
        AiopsEventService eventService = mock(AiopsEventService.class);
        AiopsRunbookService runbookService = mock(AiopsRunbookService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AiopsEventVO operationSource = new AiopsEventVO();
        operationSource.setId(77L);
        operationSource.setSource("controlled_operation");
        operationSource.setStatus("PENDING_APPROVAL");
        when(eventService.get(77L)).thenReturn(operationSource);
        ControlledOperationServiceImpl service = new ControlledOperationServiceImpl(
                eventService, runbookService, objectMapper);
        ControlledOperationPlanRequestDTO request = new ControlledOperationPlanRequestDTO();
        request.setActionCode("RESET_DEMO_SERVICE_FAULT");
        request.setSourceEventId(77L);
        request.setParams(Map.of("service", "marketplace-demo-worker"));

        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.createPlan(request));

        AiopsEventVO terminalSource = new AiopsEventVO();
        terminalSource.setId(78L);
        terminalSource.setSource("alertmanager");
        terminalSource.setStatus("RESOLVED");
        when(eventService.get(78L)).thenReturn(terminalSource);
        when(eventService.list(null, null, 100)).thenReturn(List.of());
        request.setSourceEventId(78L);
        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.createPlan(request));
        verify(eventService, times(0)).createOrUpdate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dockerPlanRejectsInfrastructureArgumentsEvenForAllowlistedTarget() {
        AiopsEventService eventService = mock(AiopsEventService.class);
        AiopsRunbookService runbookService = mock(AiopsRunbookService.class);
        DockerRestartRunbook dockerRunbook = mock(DockerRestartRunbook.class);
        when(dockerRunbook.targetId()).thenReturn("marketplace-demo-web");
        ControlledOperationServiceImpl service = new ControlledOperationServiceImpl(
                eventService, runbookService, new ObjectMapper());
        service.setDockerRestartRunbook(dockerRunbook);
        ControlledOperationPlanRequestDTO request = new ControlledOperationPlanRequestDTO();
        request.setActionCode("DOCKER_RESTART_VERIFY_ROLLBACK");
        request.setParams(Map.of(
                "targetId", "marketplace-demo-web",
                "containerName", "postgres"));

        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.createPlan(request)).getMessage().contains("只接受逻辑 targetId"));
    }

    private AiopsEventVO operationEvent(ObjectMapper objectMapper, Long id, Long sourceEventId) throws Exception {
        AiopsEventVO operation = new AiopsEventVO();
        operation.setId(id);
        operation.setEventKey("operation-source-42-RESET_DEMO_SERVICE_FAULT-diagnosis-1");
        operation.setSource("controlled_operation");
        operation.setTraceId("trace-100");
        operation.setStatus("PENDING_APPROVAL");
        operation.setMetadataJson(objectMapper.writeValueAsString(Map.of(
                "operationPlan", true,
                "actionCode", "RESET_DEMO_SERVICE_FAULT",
                "riskLevel", "MEDIUM",
                "sourceEventId", sourceEventId,
                "diagnosisHash", "diagnosis-1",
                "idempotencyKey", "operation-source-42-RESET_DEMO_SERVICE_FAULT-diagnosis-1",
                "params", Map.of("service", "marketplace-demo-worker"))));
        operation.setActions(new ArrayList<>());
        return operation;
    }
}
