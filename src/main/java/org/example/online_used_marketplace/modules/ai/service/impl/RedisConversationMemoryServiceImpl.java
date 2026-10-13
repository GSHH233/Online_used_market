package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.config.ChatMemoryProperties;
import org.example.online_used_marketplace.modules.ai.memory.MemoryScope;
import org.example.online_used_marketplace.modules.ai.service.ConversationMemoryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Atomic Q/A append keeps a turn together across application instances. */
@Service
@ConditionalOnProperty(prefix = "ai.chat.memory", name = "store", havingValue = "redis", matchIfMissing = true)
public class RedisConversationMemoryServiceImpl implements ConversationMemoryService {

    private static final DefaultRedisScript<Long> APPEND_TURN = new DefaultRedisScript<>("""
            redis.call('RPUSH', KEYS[1], ARGV[1], ARGV[2])
            redis.call('LTRIM', KEYS[1], -tonumber(ARGV[3]), -1)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
            return redis.call('LLEN', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ChatMemoryProperties properties;

    public RedisConversationMemoryServiceImpl(StringRedisTemplate redis, ChatMemoryProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public List<String> getRecentMessages(MemoryScope scope, int limit) {
        if (!properties.isEnabled() || scope == null) {
            return List.of();
        }
        long count = Math.min(Math.max(1, limit), maxMessages());
        List<String> messages = redis.opsForList().range(key(scope), -count, -1L);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    @Override
    public void appendTurn(MemoryScope scope, String question, String answer) {
        if (!properties.isEnabled() || scope == null) {
            return;
        }
        Long result = redis.execute(APPEND_TURN, List.of(key(scope)),
                "Q: " + safe(question), "A: " + safe(answer), Long.toString(maxMessages()),
                Long.toString(Math.max(1L, properties.getTtlMinutes()) * 60L));
        if (result == null) {
            throw new IllegalStateException("Redis conversation append returned no result");
        }
    }

    private long maxMessages() {
        return Math.max(1L, properties.getMaxTurns()) * 2L;
    }

    static String key(MemoryScope scope) {
        // Length-prefix prevents owner/session delimiter collisions; no raw content is stored in keys.
        String identity = scope.userId().length() + ":" + scope.userId() + scope.sessionId();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return "ai:mem:v2:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
