package org.example.online_used_marketplace.modules.user.service;

import org.example.online_used_marketplace.modules.user.dto.SmsLoginDTO;
import org.example.online_used_marketplace.modules.user.dto.SmsSendDTO;
import org.example.online_used_marketplace.modules.user.dto.UserLoginDTO;
import org.example.online_used_marketplace.modules.user.dto.UserRegisterDTO;
import org.example.online_used_marketplace.modules.user.vo.UserLoginVO;
import org.example.online_used_marketplace.modules.user.vo.UserRegisterVO;

public interface UserService {

    UserRegisterVO register(UserRegisterDTO dto);
    UserLoginVO login(UserLoginDTO dto);
    void sendSmsCode(SmsSendDTO dto);
    UserLoginVO smsLogin(SmsLoginDTO dto);
}
