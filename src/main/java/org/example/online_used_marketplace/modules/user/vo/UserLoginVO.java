package org.example.online_used_marketplace.modules.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginVO {

    private Long userId;

    private String username;

    private String nickname;

    private String role;

    private String token;

    private Long expiresIn;
}
