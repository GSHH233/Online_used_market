package org.example.online_used_marketplace.modules.ai.controller;

import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.modules.ai.agent.AgentToolRegistry;
import org.example.online_used_marketplace.modules.ai.agent.harness.ToolExecutionHarness;
import org.example.online_used_marketplace.modules.ai.agent.tools.ProfileAgentTool;
import org.example.online_used_marketplace.modules.ai.dto.HarnessDebugExecuteRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.service.MemoryGraphService;
import org.example.online_used_marketplace.modules.ai.service.UserProfileService;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class MemoryDebugAuthorizationTest {

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userCannotReadOtherUsersMemory() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        L2MemoryDebugController controller = new L2MemoryDebugController(l2, mock(UserProfileService.class));
        authenticate(1L, "USER");

        assertThatThrownBy(() -> controller.query("2", "shared", "手机", 5, 0.4D))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(l2);
    }

    @Test
    void internalCannotReadAnotherUsersGraph() {
        MemoryGraphService graph = mock(MemoryGraphService.class);
        MemoryGraphDebugController controller = new MemoryGraphDebugController(graph);
        authenticate(1L, "INTERNAL");

        assertThatThrownBy(() -> controller.nodes("2", null, 5))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(graph);
    }

    @Test
    void debugWritesAreDisabledByDefault() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        L2MemoryDebugController controller = new L2MemoryDebugController(l2, mock(UserProfileService.class));
        ReflectionTestUtils.setField(controller, "debugEnabled", false);
        authenticate(1L, "ADMIN");

        assertThatThrownBy(() -> controller.write("1", "shared", "FACT", "私有内容", 0.5D, "{}"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(l2);
    }

    @Test
    void debugReadsAreDisabledByDefault() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        L2MemoryDebugController controller = new L2MemoryDebugController(l2, mock(UserProfileService.class));
        authenticate(1L, "INTERNAL");

        assertThatThrownBy(() -> controller.query("1", "shared", "手机", 5, 0.4D))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(l2);
    }

    @Test
    void harnessDebugCannotUseProfileToolForAnotherUser() {
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        ToolExecutionHarness harness = mock(ToolExecutionHarness.class);
        when(registry.find("profile")).thenReturn(java.util.Optional.of(new ProfileAgentTool(mock(UserProfileService.class))));
        HarnessDebugController controller = new HarnessDebugController(registry, harness);
        HarnessDebugExecuteRequestDTO request = new HarnessDebugExecuteRequestDTO();
        request.setTool("profile");
        request.setMode("INTERNAL");
        request.setInput(java.util.Map.of("userId", "2", "sessionId", "shared"));
        authenticate(1L, "INTERNAL");

        assertThatThrownBy(() -> controller.execute(request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(harness);
    }

    private void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(userId, "test", role), null, List.of()));
    }
}
