package org.example.online_used_marketplace.modules.ai.agent.runtime;

import org.example.online_used_marketplace.modules.ai.memory.MemoryScope;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.example.online_used_marketplace.modules.user.enums.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

/** Trusted identity captured at the authenticated boundary and persisted for async execution. */
public record AgentExecutionIdentity(String userId, String role) {

    public AgentExecutionIdentity {
        userId = StringUtils.hasText(userId) ? userId.trim() : "";
        role = UserRole.normalize(role);
    }

    public static AgentExecutionIdentity current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.getUserId() != null) {
            return new AgentExecutionIdentity(principal.getUserId().toString(), principal.getRole());
        }
        return anonymous();
    }

    public static AgentExecutionIdentity anonymous() {
        return new AgentExecutionIdentity("", UserRole.USER.name());
    }

    public boolean authenticated() {
        return StringUtils.hasText(userId);
    }

    public boolean isAdmin() {
        return UserRole.ADMIN.name().equals(role);
    }

    public String effectiveMode(String requestedMode) {
        return "INTERNAL".equalsIgnoreCase(requestedMode) && UserRole.canUseInternal(role)
                ? "INTERNAL" : "USER";
    }

    public MemoryScope scope(String sessionId) {
        return authenticated() && StringUtils.hasText(sessionId)
                ? new MemoryScope(userId, sessionId) : null;
    }
}
