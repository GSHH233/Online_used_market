package org.example.online_used_marketplace.config;

import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class RagChatRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);
    private final StringRedisTemplate redis;
    private final int perMinute;

    public RagChatRateLimiter(StringRedisTemplate redis,
                             @Value("${ai.chat.rate-limit-per-minute:10}") int perMinute) {
        if (perMinute < 1) {
            throw new IllegalArgumentException("WebSocket rate limit must be positive");
        }
        this.redis = redis;
        this.perMinute = perMinute;
    }

    public boolean tryAcquire(String userId) {
        Long count;
        try {
            count = redis.execute(SCRIPT, List.of("ws:rag-chat:rate:" + userId), "60");
            if (count == null || count < 1) {
                throw new IllegalStateException("Invalid rate limit result");
            }
        } catch (Exception ex) {
            log.warn("WebSocket rate limiter unavailable, userId={}", userId);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "聊天限流服务暂不可用，请稍后重试");
        }
        return count <= perMinute;
    }
}
