package org.example.online_used_marketplace.modules.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.user.dto.SmsLoginDTO;
import org.example.online_used_marketplace.modules.user.dto.SmsSendDTO;
import org.example.online_used_marketplace.modules.user.dto.UserLoginDTO;
import org.example.online_used_marketplace.modules.user.dto.UserRegisterDTO;
import org.example.online_used_marketplace.modules.user.entity.User;
import org.example.online_used_marketplace.modules.user.service.UserService;
import org.example.online_used_marketplace.modules.user.vo.UserLoginVO;
import org.example.online_used_marketplace.modules.user.vo.UserRegisterVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<UserRegisterVO> register(@Valid @RequestBody UserRegisterDTO dto) {
        return Result.success(userService.register(dto));
    }
    @PostMapping({"/Login", "/login"})
    public Result<UserLoginVO> login(@Valid @RequestBody UserLoginDTO dto){
        return Result.success(userService.login(dto));
    }
    @PostMapping("/sms/send")
    public Result Send(@Valid @RequestBody SmsSendDTO dto){
        userService.sendSmsCode(dto);
        return Result.success();
    }
    @PostMapping("/sms/login")
    public Result<UserLoginVO> Send(@Valid @RequestBody SmsLoginDTO dto){
        return Result.success(userService.smsLogin(dto));
    }
}
