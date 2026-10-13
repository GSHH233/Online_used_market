package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.config.ChatMemoryProperties;
import org.example.online_used_marketplace.modules.ai.memory.MemoryScope;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisConversationMemoryServiceImplTest {

    @Test
    void atomicTurnScriptReceivesBothMessagesBoundAndTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setMaxTurns(3);
        properties.setTtlMinutes(2);
        MemoryScope scope = new MemoryScope("1", "session");
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(2L);

        new RedisConversationMemoryServiceImpl(redis, properties).appendTurn(scope, " hello ", " answer ");

        verify(redis).execute(any(RedisScript.class), eq(List.of(RedisConversationMemoryServiceImpl.key(scope))),
                eq("Q: hello"), eq("A: answer"), eq("6"), eq("120"));
    }

    @Test
    void anotherInstanceReadsTheSameRedisScopeWithoutLocalCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        MemoryScope scope = new MemoryScope("1", "shared");
        when(lists.range(RedisConversationMemoryServiceImpl.key(scope), -2L, -1L))
                .thenReturn(List.of("Q: phone", "A: advice"));
        var first = new RedisConversationMemoryServiceImpl(redis, new ChatMemoryProperties());
        var second = new RedisConversationMemoryServiceImpl(redis, new ChatMemoryProperties());

        assertThat(first.getRecentMessages(scope, 2)).containsExactly("Q: phone", "A: advice");
        assertThat(second.getRecentMessages(scope, 2)).containsExactly("Q: phone", "A: advice");
    }

    @Test
    void ownerAndSessionKeysCannotCollideOrLeakAcrossUsers() {
        assertThat(RedisConversationMemoryServiceImpl.key(new MemoryScope("1", "same")))
                .isNotEqualTo(RedisConversationMemoryServiceImpl.key(new MemoryScope("2", "same")))
                .isNotEqualTo(RedisConversationMemoryServiceImpl.key(new MemoryScope("1", "other")));
        assertThat(RedisConversationMemoryServiceImpl.key(new MemoryScope("1:2", "3")))
                .isNotEqualTo(RedisConversationMemoryServiceImpl.key(new MemoryScope("1", "2:3")));
    }

    @Test
    void disabledMemoryDoesNotTouchRedisAndMissingScriptResultIsNotSuccess() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setEnabled(false);
        var service = new RedisConversationMemoryServiceImpl(redis, properties);
        MemoryScope scope = new MemoryScope("1", "shared");
        service.appendTurn(scope, "q", "a");
        assertThat(service.getRecentMessages(scope, 2)).isEmpty();
        verifyNoInteractions(redis);
        properties.setEnabled(true);
        assertThatThrownBy(() -> service.appendTurn(scope, "q", "a"))
                .isInstanceOf(IllegalStateException.class);
    }
}
