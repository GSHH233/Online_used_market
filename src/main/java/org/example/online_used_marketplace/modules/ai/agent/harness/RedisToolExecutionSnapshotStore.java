package org.example.online_used_marketplace.modules.ai.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class RedisToolExecutionSnapshotStore implements ToolExecutionSnapshotStore {

    private static final String KEY_PREFIX = "ai:agent:harness:snapshot:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void save(String key, ToolQueryResultVO observation, Duration ttl) {
        if (key == null || key.isBlank() || observation == null) {
            return;
        }
        try {
            String value = objectMapper.writeValueAsString(observation);
            redisTemplate.opsForValue().set(redisKey(key), value, normalizeTtl(ttl));
        } catch (Exception ex) {
            log.warn("save harness snapshot to redis failed, key={}, message={}", key, ex.getMessage());
        }
    }

    @Override
    public Optional<ToolQueryResultVO> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(redisKey(key));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, ToolQueryResultVO.class));
        } catch (Exception ex) {
            log.warn("restore harness snapshot from redis failed, key={}, message={}", key, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(redisKey(key));
        } catch (Exception ex) {
            log.warn("delete harness snapshot from redis failed, key={}, message={}", key, ex.getMessage());
        }
    }

    private String redisKey(String key) {
        return KEY_PREFIX + key;
    }

    private Duration normalizeTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Duration.ofMinutes(10);
        }
        return ttl;
    }
}
