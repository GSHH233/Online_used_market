package org.example.online_used_marketplace.modules.ai.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DefaultToolExecutionSnapshotStore implements ToolExecutionSnapshotStore {

    private static final String KEY_PREFIX = "ai:agent:harness:snapshot:";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final Map<String, SnapshotEntry> memorySnapshots = new ConcurrentHashMap<>();

    public DefaultToolExecutionSnapshotStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                             ObjectMapper objectMapper) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String key, ToolQueryResultVO observation, Duration ttl) {
        if (key == null || key.isBlank() || observation == null) {
            return;
        }
        Duration safeTtl = normalizeTtl(ttl);
        saveMemory(key, observation, safeTtl);
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }
        try {
            String value = objectMapper.writeValueAsString(observation);
            redisTemplate.opsForValue().set(redisKey(key), value, safeTtl);
        } catch (Exception ex) {
            log.warn("save harness snapshot to redis failed, key={}, message={}", key, errorMessage(ex));
        }
    }

    @Override
    public Optional<ToolQueryResultVO> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Optional<ToolQueryResultVO> redisSnapshot = findRedis(key);
        if (redisSnapshot.isPresent()) {
            return redisSnapshot;
        }
        return findMemory(key);
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        memorySnapshots.remove(key);
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(redisKey(key));
        } catch (Exception ex) {
            log.warn("delete harness snapshot from redis failed, key={}, message={}", key, errorMessage(ex));
        }
    }

    private Optional<ToolQueryResultVO> findRedis(String key) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(redisKey(key));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, ToolQueryResultVO.class));
        } catch (Exception ex) {
            log.warn("restore harness snapshot from redis failed, key={}, message={}", key, errorMessage(ex));
            return Optional.empty();
        }
    }

    private void saveMemory(String key, ToolQueryResultVO observation, Duration ttl) {
        memorySnapshots.put(key, new SnapshotEntry(observation, Instant.now().toEpochMilli(), ttl.toMillis()));
    }

    private Optional<ToolQueryResultVO> findMemory(String key) {
        SnapshotEntry entry = memorySnapshots.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().toEpochMilli() - entry.createdAtMs() > entry.ttlMs()) {
            memorySnapshots.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.observation());
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

    private String errorMessage(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return ex.getMessage();
        }
        Throwable cause = ex.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return ex.getClass().getSimpleName();
    }

    private record SnapshotEntry(ToolQueryResultVO observation, long createdAtMs, long ttlMs) {
    }
}