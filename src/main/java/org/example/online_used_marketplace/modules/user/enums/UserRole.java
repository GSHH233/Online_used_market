package org.example.online_used_marketplace.modules.user.enums;

import org.springframework.util.StringUtils;

public enum UserRole {
    USER,
    ADMIN,
    INTERNAL;

    public static String normalize(String role) {
        if (!StringUtils.hasText(role)) {
            return USER.name();
        }
        try {
            return UserRole.valueOf(role.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            return USER.name();
        }
    }

    public static boolean canUseInternal(String role) {
        String normalized = normalize(role);
        return ADMIN.name().equals(normalized) || INTERNAL.name().equals(normalized);
    }
}
