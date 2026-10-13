package org.example.online_used_marketplace.modules.ai.controller;

import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.modules.ai.service.AgentRuntimeService;
import org.example.online_used_marketplace.modules.ai.vo.AgentRuntimeRunVO;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class AgentRuntimeAuthorizationTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void internalUserOnlySeesOwnedRuns() {
        authenticate(2L, "INTERNAL");
        AgentRuntimeService service = mock(AgentRuntimeService.class);
        AgentRuntimeRunVO own = run("own", "2");
        AgentRuntimeRunVO foreign = run("foreign", "1");
        when(service.list(100)).thenReturn(List.of(foreign, own));
        when(service.get("foreign")).thenReturn(foreign);
        AgentRuntimeController controller = new AgentRuntimeController(service);

        assertThat(controller.list(20).getData()).containsExactly(own);
        assertThatThrownBy(() -> controller.get("foreign"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问");
        verify(service).recordAccessAudit(
                "foreign", "READ_DETAIL", "DENY", "actor is not the Runtime owner");
    }

    @Test
    void adminCanReadAnotherUsersRun() {
        authenticate(1L, "ADMIN");
        AgentRuntimeService service = mock(AgentRuntimeService.class);
        AgentRuntimeRunVO foreign = run("foreign", "2");
        when(service.get("foreign")).thenReturn(foreign);
        AgentRuntimeController controller = new AgentRuntimeController(service);

        assertThat(controller.get("foreign").getData()).isSameAs(foreign);
    }

    private void authenticate(Long userId, String role) {
        UserPrincipal principal = new UserPrincipal(userId, "test-" + userId, role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private AgentRuntimeRunVO run(String runId, String ownerUserId) {
        AgentRuntimeRunVO run = new AgentRuntimeRunVO();
        run.setRunId(runId);
        run.setOwnerUserId(ownerUserId);
        return run;
    }
}
