package org.example.online_used_marketplace.modules.ai.controller;

import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.example.online_used_marketplace.modules.user.enums.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

final class ToolAccessGuard {

    private ToolAccessGuard() {
    }

    static String normalizeMode(String mode) {
        return "INTERNAL".equalsIgnoreCase(mode) ? "INTERNAL" : "USER";
    }

    static String requireInternal(String mode, String toolName) {
        String normalized = normalizeMode(mode);
        if (!"INTERNAL".equals(normalized)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "USER模式不允许直接调用内部工具：" + toolName);
        }
        requireInternalRole(toolName);
        return normalized;
    }

    static void validateInternalModeIfRequested(String mode, String target) {
        if ("INTERNAL".equals(normalizeMode(mode))) {
            requireInternalRole(target);
        }
    }

    static void requireInternalEndpoint(String target) {
        requireInternalRole(target);
    }

    static void requireAdminEndpoint(String target) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未认证或登录已过期");
        }
        String role = StringUtils.hasText(principal.getRole()) ? principal.getRole() : "USER";
        if (!UserRole.ADMIN.name().equals(UserRole.normalize(role))) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "当前用户角色无权执行高风险操作：" + target + "，role=" + UserRole.normalize(role));
        }
    }

    private static void requireInternalRole(String target) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未认证或登录已过期");
        }
        String role = StringUtils.hasText(principal.getRole()) ? principal.getRole() : "USER";
        if (!UserRole.canUseInternal(role)) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "当前用户角色无权使用INTERNAL模式：" + target + "，role=" + UserRole.normalize(role));
        }
    }
}
