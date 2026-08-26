package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.modules.ai.aiops.demo.AiopsDemoService;
import org.example.online_used_marketplace.modules.ai.aiops.demo.vo.AiopsDemoServiceStateVO;
import org.example.online_used_marketplace.modules.ai.aiops.docker.DockerRestartResult;
import org.example.online_used_marketplace.modules.ai.aiops.docker.DockerRestartRunbook;
import org.example.online_used_marketplace.modules.ai.aiops.docker.DockerRunbookOutcome;
import org.example.online_used_marketplace.modules.ai.dto.AiopsRunbookExecuteRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AiopsEventService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeIndexService;
import org.example.online_used_marketplace.modules.ai.vo.AiopsEventVO;
import org.example.online_used_marketplace.modules.ai.vo.AiopsRunbookActionVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiopsRunbookServiceImplTest {

    @Test
    void resetDemoFaultReadsStateAgainAndUpdatesSourceEvent() {
        KnowledgeIndexService knowledgeIndexService = mock(KnowledgeIndexService.class);
        AiopsEventService eventService = mock(AiopsEventService.class);
        AiopsDemoService demoService = mock(AiopsDemoService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiopsRunbookServiceImpl service = new AiopsRunbookServiceImpl(
                knowledgeIndexService, eventService, new ObjectMapper(), jdbcTemplate);
        service.setAiopsDemoService(demoService);

        AiopsDemoServiceStateVO state = new AiopsDemoServiceStateVO();
        state.setService("marketplace-demo-worker");
        state.setHealth(1);
        state.setErrorRatio(0.0D);
        state.setFaulted(false);
        when(demoService.getState("marketplace-demo-worker")).thenReturn(state);
        AiopsEventVO sourceResolved = new AiopsEventVO();
        sourceResolved.setId(401L);
        sourceResolved.setStatus("RESOLVED");
        when(eventService.get(401L)).thenReturn(sourceEvent("OPEN"));
        when(eventService.markResolved(eq(401L), eq("AUTO_VERIFIED"), anyString())).thenReturn(sourceResolved);
        doReturn(901L).when(jdbcTemplate).queryForObject(anyString(), eq(Long.class), any(Object[].class));

        AiopsRunbookExecuteRequestDTO request = new AiopsRunbookExecuteRequestDTO();
        request.setActionCode("RESET_DEMO_SERVICE_FAULT");
        request.setEventId(501L);
        request.setIdempotencyKey("reset-901");
        request.setDryRun(false);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("service", "marketplace-demo-worker");
        params.put("__sourceEventId", 401L);
        request.setParams(params);

        AiopsRunbookActionVO result = service.execute(request);

        verify(demoService).resetFault("marketplace-demo-worker", "reset-901");
        verify(demoService).getState("marketplace-demo-worker");
        verify(eventService).markResolved(eq(401L), eq("AUTO_VERIFIED"), anyString());
        assertEquals("SUCCESS", result.getStatus());
        assertEquals("PASSED", result.getVerificationStatus());
        assertEquals(401L, ((Map<?, ?>) result.getResult().get("sourceEventSync")).get("sourceEventId"));
    }

    @Test
    void dockerRollbackRecoveryRemainsVisibleInPersistedRunbookResult() {
        KnowledgeIndexService knowledgeIndexService = mock(KnowledgeIndexService.class);
        AiopsEventService eventService = mock(AiopsEventService.class);
        DockerRestartRunbook dockerRunbook = mock(DockerRestartRunbook.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiopsRunbookServiceImpl service = new AiopsRunbookServiceImpl(
                knowledgeIndexService, eventService, new ObjectMapper(), jdbcTemplate);
        service.setDockerRestartRunbook(dockerRunbook);
        when(dockerRunbook.targetId()).thenReturn("marketplace-demo-web");
        when(dockerRunbook.execute("marketplace-demo-web")).thenReturn(new DockerRestartResult(
                true, true, true, DockerRunbookOutcome.HEALTHY_AFTER_ROLLBACK,
                "rollback restored health", List.of()));
        doReturn(902L).when(jdbcTemplate).queryForObject(anyString(), eq(Long.class), any(Object[].class));

        AiopsRunbookExecuteRequestDTO request = new AiopsRunbookExecuteRequestDTO();
        request.setActionCode("DOCKER_RESTART_VERIFY_ROLLBACK");
        request.setEventId(502L);
        request.setIdempotencyKey("docker-rollback-902");
        request.setDryRun(false);
        request.setParams(Map.of("targetId", "marketplace-demo-web"));

        AiopsRunbookActionVO result = service.execute(request);

        assertEquals("SUCCESS", result.getStatus());
        assertEquals("ROLLED_BACK", result.getVerificationStatus());
        assertEquals(true, result.getResult().get("rollbackAttempted"));
        assertEquals("HEALTHY_AFTER_ROLLBACK", result.getResult().get("outcome"));
        verify(dockerRunbook).execute("marketplace-demo-web");
    }

    private AiopsEventVO sourceEvent(String status) {
        AiopsEventVO source = new AiopsEventVO();
        source.setId(401L);
        source.setSource("alertmanager");
        source.setStatus(status);
        return source;
    }
}
