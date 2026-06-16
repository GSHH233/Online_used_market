package org.example.online_used_marketplace.modules.ai.agent.harness;

import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class InMemoryToolExecutionSnapshotStore implements ToolExecutionSnapshotStore {

    private final Map<String, SnapshotEntry> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(String key, ToolQueryResultVO observation, Duration ttl) {
        if (key == null || key.isBlank() || observation == null) {
            return;
        }
        snapshots.put(key, new SnapshotEntry(observation, Instant.now().toEpochMilli(), normalizeTtl(ttl).toMillis()));
    }

    @Override
    public Optional<ToolQueryResultVO> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        SnapshotEntry entry = snapshots.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().toEpochMilli() - entry.createdAtMs() > entry.ttlMs()) {
            snapshots.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.observation());
    }

    @Override
    public void delete(String key) {
        if (key != null) {
            snapshots.remove(key);
        }
    }

    private Duration normalizeTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Duration.ofMinutes(10);
        }
        return ttl;
    }

    private record SnapshotEntry(ToolQueryResultVO observation, long createdAtMs, long ttlMs) {
    }
}
