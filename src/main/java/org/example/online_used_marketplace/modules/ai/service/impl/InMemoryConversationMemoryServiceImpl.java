package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.example.online_used_marketplace.modules.ai.config.ChatMemoryProperties;
import org.example.online_used_marketplace.modules.ai.memory.MemoryScope;
import org.example.online_used_marketplace.modules.ai.service.ConversationMemoryService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** L1 recent turns, isolated by authenticated owner and session. */
@Service
@ConditionalOnProperty(prefix = "ai.chat.memory", name = "store", havingValue = "memory")
public class InMemoryConversationMemoryServiceImpl implements ConversationMemoryService {

    private final ChatMemoryProperties chatMemoryProperties;
    private final Map<MemoryScope, MemoryBucket> memoryStore = new ConcurrentHashMap<>();

    public InMemoryConversationMemoryServiceImpl(ChatMemoryProperties chatMemoryProperties) {
        this.chatMemoryProperties = chatMemoryProperties;
    }

    @Override
    public List<String> getRecentMessages(MemoryScope scope, int limit) {
        if (!chatMemoryProperties.isEnabled()) {
            return List.of();
        }
        if (scope == null) {
            return List.of();
        }

        MemoryBucket bucket = memoryStore.get(scope);
        if (bucket == null) {
            return List.of();
        }
        synchronized (bucket) {
            if (isExpired(bucket)) {
                memoryStore.remove(scope, bucket);
                return List.of();
            }
            LinkedList<String> turns = bucket.getTurns();
            if (turns == null || turns.isEmpty()) {
                return List.of();
            }
            int safeLimit = Math.max(1, limit);
            int from = Math.max(0, turns.size() - safeLimit);
            return new ArrayList<>(turns.subList(from, turns.size()));
        }
    }

    @Override
    public void appendTurn(MemoryScope scope, String question, String answer) {
        if (!chatMemoryProperties.isEnabled()) {
            return;
        }
        if (scope == null) {
            return;
        }

        MemoryBucket bucket = memoryStore.computeIfAbsent(scope,
                k -> new MemoryBucket(new LinkedList<>(), Instant.now().toEpochMilli()));

        synchronized (bucket) {
            if (isExpired(bucket)) {
                bucket.setTurns(new LinkedList<>());
            }
            LinkedList<String> turns = bucket.getTurns();
            turns.add("Q: " + safe(question));
            turns.add("A: " + safe(answer));
            int maxTurns = Math.max(1, chatMemoryProperties.getMaxTurns());
            while (turns.size() > maxTurns * 2) {
                turns.removeFirst();
            }
            bucket.setUpdatedAtMillis(Instant.now().toEpochMilli());
        }
    }

    private boolean isExpired(MemoryBucket bucket) {
        long ttlMinutes = Math.max(1L, chatMemoryProperties.getTtlMinutes());
        long expireAt = bucket.getUpdatedAtMillis() + ttlMinutes * 60_000L;
        return Instant.now().toEpochMilli() > expireAt;
    }

    private String safe(String text) {
        return StringUtils.hasText(text) ? text.trim() : "";
    }

    @Data
    @AllArgsConstructor
    private static class MemoryBucket {
        private LinkedList<String> turns;
        private long updatedAtMillis;
    }
}
