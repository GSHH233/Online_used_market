package org.example.online_used_marketplace.modules.ai.alert;

import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.example.online_used_marketplace.modules.user.enums.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

final class AiopsAlertAccessGuard {

    private AiopsAlertAccessGuard() {
    }

    static void requireInternal(String target) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未认证或登录已过期");
        }
        String role = StringUtils.hasText(principal.getRole()) ? principal.getRole() : UserRole.USER.name();
        if (!UserRole.canUseInternal(role)) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "当前用户角色无权访问 AIOps 告警接口：" + target);
        }
    }
}
