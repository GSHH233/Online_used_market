package org.example.online_used_marketplace.modules.ai.memory;

import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

/** Identity established at the request boundary, never inferred from a session ID. */
public record MemoryScope(String userId, String sessionId) {

    public MemoryScope {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("Memory owner and session are required");
        }
        userId = userId.trim();
        sessionId = sessionId.trim();
    }

    public static MemoryScope current(String sessionId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)
                || principal.getUserId() == null || !StringUtils.hasText(sessionId)) {
            return null;
        }
        return new MemoryScope(principal.getUserId().toString(), sessionId);
    }
}
