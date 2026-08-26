package org.example.online_used_marketplace.modules.ai.aiops.demo;

import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.example.online_used_marketplace.modules.user.enums.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

final class AiopsDemoAccessGuard {

    private AiopsDemoAccessGuard() {
    }

    static void requireAdmin(String target) {
        UserPrincipal principal = principal(target);
        if (!UserRole.ADMIN.name().equals(UserRole.normalize(principal.getRole()))) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "当前用户角色无权注入demo故障：" + target);
        }
    }

    static void requireInternal(String target) {
        UserPrincipal principal = principal(target);
        if (!UserRole.canUseInternal(principal.getRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "当前用户角色无权查询demo状态：" + target);
        }
    }

    private static UserPrincipal principal(String target) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未认证或登录已过期：" + target);
        }
        if (!StringUtils.hasText(principal.getRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "用户角色为空：" + target);
        }
        return principal;
    }
}
