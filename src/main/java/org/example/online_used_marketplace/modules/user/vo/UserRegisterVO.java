package org.example.online_used_marketplace.modules.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserRegisterVO {

    private Long userId;

    private String username;

    private String nickname;
}
