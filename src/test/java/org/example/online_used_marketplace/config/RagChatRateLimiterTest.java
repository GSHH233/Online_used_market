package org.example.online_used_marketplace.config;

import org.example.online_used_marketplace.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagChatRateLimiterTest {
    @Test
    void firstTenRequestsPassAndEleventhFails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), eq(List.of("ws:rag-chat:rate:42")), eq("60")))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        RagChatRateLimiter limiter = new RagChatRateLimiter(redis, 10);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("42")).isTrue();
        }
        assertThat(limiter.tryAcquire("42")).isFalse();
        assertThat(limiter.tryAcquire("42")).isFalse();
    }

    @Test
    void usersHaveSeparateKeysAndQuotaRecoversAfterWindowExpiry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), eq(List.of("ws:rag-chat:rate:42")), eq("60")))
                .thenReturn(11L, 1L);
        when(redis.execute(any(RedisScript.class), eq(List.of("ws:rag-chat:rate:7")), eq("60")))
                .thenReturn(1L);
        RagChatRateLimiter limiter = new RagChatRateLimiter(redis, 10);

        assertThat(limiter.tryAcquire("42")).isFalse();
        assertThat(limiter.tryAcquire("7")).isTrue();
        assertThat(limiter.tryAcquire("42")).isTrue();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void incrementAndExpiryAreOneScriptAndExistingWindowsAreNotExtended() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), eq("60"))).thenReturn(1L);

        new RagChatRateLimiter(redis, 10).tryAcquire("42");

        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of("ws:rag-chat:rate:42")), eq("60"));
        assertThat(script.getValue().getResultType()).isEqualTo(Long.class);
        assertThat(script.getValue().getScriptAsString()).isEqualTo("""
                local count = redis.call('INCR', KEYS[1])
                if count == 1 or redis.call('TTL', KEYS[1]) < 0 then
                    redis.call('EXPIRE', KEYS[1], ARGV[1])
                end
                return count
                """);
        verifyNoMoreInteractions(redis);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidRedisResultsNeverFailOpen(Long count) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), eq("60"))).thenReturn(count);

        assertThatThrownBy(() -> new RagChatRateLimiter(redis, 10).tryAcquire("42"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(500));
    }

    @Test
    void redisOutageNeverFailsOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), eq("60")))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> new RagChatRateLimiter(redis, 10).tryAcquire("42"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(500));
    }

    @Test
    void configuredLimitIsHonoredAndCannotDisableProtection() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), eq("60"))).thenReturn(2L, 3L);
        RagChatRateLimiter limiter = new RagChatRateLimiter(redis, 2);

        assertThat(limiter.tryAcquire("42")).isTrue();
        assertThat(limiter.tryAcquire("42")).isFalse();
        assertThatThrownBy(() -> new RagChatRateLimiter(redis, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagChatRateLimiter(redis, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
