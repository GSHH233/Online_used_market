package org.example.online_used_marketplace.modules.user.entity;

import lombok.Data;

import java.security.Principal;

/**
 * @author Admin
 * @ClassName: UserPrincipal
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-03  0:08
 * @Version:
 */
@Data
public class UserPrincipal implements Principal {
    private final Long userId;
    private final String username;
    private final String role;

    public UserPrincipal(Long userId, String username) {
        this(userId, username, "USER");
    }

    public UserPrincipal(Long userId, String username, String role) {
        this.userId = userId;
        this.username = username;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    @Override
    public String getName() {
        return username;
    }
}
