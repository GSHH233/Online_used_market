package org.example.online_used_marketplace.modules.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.user.dto.SmsLoginDTO;
import org.example.online_used_marketplace.modules.user.dto.SmsSendDTO;
import org.example.online_used_marketplace.modules.user.dto.UserLoginDTO;
import org.example.online_used_marketplace.modules.user.dto.UserRegisterDTO;
import org.example.online_used_marketplace.modules.user.entity.User;
import org.example.online_used_marketplace.modules.user.enums.UserRole;
import org.example.online_used_marketplace.modules.user.mapper.UserMapper;
import org.example.online_used_marketplace.modules.user.service.UserService;
import org.example.online_used_marketplace.modules.user.vo.UserLoginVO;
import org.example.online_used_marketplace.modules.user.vo.UserRegisterVO;
import org.example.online_used_marketplace.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    @Value("${jwt.expiration}")
    private long expiration;
    @Value("${sms.code-expire-seconds}")
    private long expires;
    @Value("${sms.send-interval-seconds}")
    private long interval;
    private static final String CODE_PREFIX="login:sms:code:";
    private static final String LIMIT_PREFIX="login:sms:limit:";


    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserRegisterVO register(UserRegisterDTO dto) {
        if (!dto.getPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "两次输入的密码不一致");
        }

        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, dto.getUsername()));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "用户名已存在");
        }
        Long countphone = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, dto.getPhone()));
        if (countphone != null && countphone > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "手机号已使用");
        }

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setPhone(dto.getPhone());
        user.setStatus(1);
        user.setRole(UserRole.USER.name());
        user.setCreatedTime(LocalDateTime.now());
        user.setUpdatedTime(LocalDateTime.now());
        user.setIsDeleted(0);

        int rows = userMapper.insert(user);
        if (rows != 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "注册失败，请稍后重试");
        }

        return new UserRegisterVO(user.getId(), user.getUsername(), user.getNickname());
    }

    @Override
    public UserLoginVO login(UserLoginDTO dto) {
        User user=null;
        if ("USERNAME_PASSWORD".equals(dto.getLoginType())){
            user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, dto.getAccount()));
        }else if("PHONE_PASSWORD".equals(dto.getLoginType())){
            user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getPhone, dto.getAccount()));
        }else {
            throw new BusinessException(ResultCode.BAD_REQUEST,"账号或密码错误");
        }
        if (user==null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (user.getStatus() == null||user.getStatus()!=1) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号或密码错误");
        }
        String role = UserRole.normalize(user.getRole());
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), role);
        LocalDateTime now = LocalDateTime.now();
        user.setLastLoginTime(now);
        int count = userMapper.updateById(user);
        if(count!=1){
            throw new BusinessException(ResultCode.INTERNAL_ERROR,"更新登录时间失败");
        }
        UserLoginVO vo = new UserLoginVO();
        vo.setNickname(user.getNickname());
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRole(role);
        vo.setToken(token);
        vo.setExpiresIn(expiration);
        return vo;
    }

    @Override
    public void sendSmsCode(SmsSendDTO dto) {
        int randomNum = ThreadLocalRandom.current().nextInt(100000, 1000000);
        String code=String.valueOf(randomNum);
        String phone= dto.getPhone();
        String codeKey=CODE_PREFIX+phone;
        String limit=LIMIT_PREFIX+phone;
        String s = redisTemplate.opsForValue().get(limit);
        if (StringUtils.hasText(s) && !s.isBlank()){
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求过于频繁，请稍后再试");
        }
        redisTemplate.opsForValue().set(codeKey,code,expires, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(limit,"1",interval,TimeUnit.SECONDS);
        log.info("短信验证码 phone={}, code={}", phone, code);
    }

    @Override
    public UserLoginVO smsLogin(SmsLoginDTO dto) {
        String phone = dto.getPhone();
        String codeKey = CODE_PREFIX + phone;
        String cachedCode = redisTemplate.opsForValue().get(codeKey);
        if (!StringUtils.hasText(cachedCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码已过期或不存在");
        }
        if (!dto.getCode().equals(cachedCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "验证码错误");
        }
        if (!redisTemplate.delete(codeKey)) {
            log.warn("验证码删除失败, phone={}", phone);
        }

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            user = findOrCreateByPhone(phone);
        }

        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户状态异常");
        }

        user.setLastLoginTime(LocalDateTime.now());
        int update = userMapper.updateById(user);
        if (update != 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "更新登录时间失败");
        }

        String role = UserRole.normalize(user.getRole());
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), role);
        UserLoginVO vo = buildLoginVO(user);
        vo.setRole(role);
        vo.setToken(token);
        return vo;
    }

    private User findOrCreateByPhone(String phone) {
        User exists = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (exists != null) {
            return exists;
        }

        User newuser = new User();
        newuser.setPhone(phone);
        newuser.setUsername("u_" + phone.substring(5));
        newuser.setNickname("用户" + phone.substring(7));
        newuser.setStatus(1);
        newuser.setRole(UserRole.USER.name());
        newuser.setCreatedTime(LocalDateTime.now());
        newuser.setUpdatedTime(LocalDateTime.now());
        newuser.setIsDeleted(0);

        try {
            int rows = userMapper.insert(newuser);
            if (rows != 1) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "服务器出错");
            }
            return newuser;
        } catch (Exception e) {
            User retry = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
            if (retry != null) {
                return retry;
            }
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "服务器出错");
        }
    }
    private UserLoginVO buildLoginVO(User user){
        UserLoginVO vo = new UserLoginVO();
        vo.setNickname(user.getNickname());
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRole(UserRole.normalize(user.getRole()));
        vo.setExpiresIn(expiration);
        return vo;
    }
}
