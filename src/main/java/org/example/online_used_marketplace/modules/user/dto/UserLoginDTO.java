package org.example.online_used_marketplace.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserLoginDTO {

    /**
     * 登录账号：可以传用户名，也可以传手机号
     */
    @NotBlank(message = "登录账号不能为空")
    @Size(min = 4, max = 32, message = "登录账号长度需在4-32位之间")
    private String account;

    /**
     * 登录密码
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度需在6-20位之间")
    private String password;

    /**
     * 登录方式：USERNAME_PASSWORD 或 PHONE_PASSWORD
     */
    @NotBlank(message = "登录方式不能为空")
    @Pattern(regexp = "^(USERNAME_PASSWORD|PHONE_PASSWORD)$", message = "登录方式不正确")
    private String loginType;
}
