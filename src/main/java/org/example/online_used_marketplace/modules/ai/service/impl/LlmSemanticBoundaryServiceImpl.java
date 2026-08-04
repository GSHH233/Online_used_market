package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.document.ParsedSection;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.service.SemanticBoundaryService;
import org.example.online_used_marketplace.modules.ai.service.TokenCounterService;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationRequest;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSemanticBoundaryServiceImpl implements SemanticBoundaryService {

    private static final String PROMPT_VERSION = "semantic-boundary-v1";
    private final ChatGenerationService chatGenerationService;
    private final ObjectMapper objectMapper;
    private final KnowledgeIngestionProperties properties;
    private final TokenCounterService tokenCounterService;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "semantic-boundary");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public List<SemanticGroup> split(ParsedSection section, List<String> paragraphs) {
        if (!properties.getChunking().isSemanticEnabled() || paragraphs.size() < 3) {
            return List.of();
        }
        String key = hash(String.join(" > ", section.getHeadingPath())
                + "|" + String.join("\n", paragraphs)
                + "|" + properties.getIngestion().getParserVersion()
                + "|" + properties.getChunking().getStrategyVersion()
                + "|" + properties.getChunking().getSemanticModelId()
                + "|" + PROMPT_VERSION);
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.groups();
        }
        if (cached != null) {
            cache.remove(key, cached);
        }
        Future<List<SemanticGroup>> future = executor.submit(() -> splitWithWindows(section, paragraphs));
        try {
            List<SemanticGroup> result = future.get(
                    properties.getChunking().getSemanticTimeoutMs(), TimeUnit.MILLISECONDS);
            validateCoverage(result, paragraphs.size());
            List<SemanticGroup> immutableResult = List.copyOf(result);
            putCache(key, new CacheEntry(
                    immutableResult,
                    System.currentTimeMillis() + semanticCacheTtlMs()));
            return immutableResult;
        } catch (Exception ex) {
            future.cancel(true);
            log.warn("[Chunking] semantic split fallback, section={}, reason={}",
                    section.getSectionId(), ex.getMessage());
            return List.of();
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private List<SemanticGroup> callModel(ParsedSection section, List<String> paragraphs) throws Exception {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            input.append("[P").append(i).append("] ").append(paragraphs.get(i)).append('\n');
        }
        String prompt = """
                你是知识库切片边界识别器。只按照主题连续性对给定段落分组，不得改写、遗漏、复制或改变段落顺序。
                返回严格JSON：{"groups":[{"start":0,"end":2,"topic":"主题"}]}。
                groups必须从0开始连续覆盖到最后一个段落，start/end均为闭区间。
                标题路径：%s
                段落：
                %s
                """.formatted(String.join(" > ", section.getHeadingPath()), input);
        String raw = chatGenerationService.generate(TokenGenerationRequest.builder()
                .scene(TokenScene.SEMANTIC_CHUNKING)
                .systemPrompt("只输出JSON，不输出Markdown代码块或解释。")
                .prompt(prompt)
                .build()).getContent();
        JsonNode root = objectMapper.readTree(cleanJson(raw));
        List<SemanticGroup> groups = new ArrayList<>();
        for (JsonNode node : root.path("groups")) {
            groups.add(new SemanticGroup(
                    node.path("start").asInt(-1),
                    node.path("end").asInt(-1),
                    node.path("topic").asText("")));
        }
        return groups;
    }

    private List<SemanticGroup> splitWithWindows(ParsedSection section,
                                                  List<String> paragraphs) throws Exception {
        int budget = Math.max(1, properties.getChunking().getSemanticWindowTokens());
        List<SemanticGroup> out = new ArrayList<>();
        int windowStart = 0;
        while (windowStart < paragraphs.size()) {
            int windowEnd = windowStart;
            int used = 0;
            while (windowEnd < paragraphs.size()) {
                int next = tokenCounterService.count(paragraphs.get(windowEnd));
                if (windowEnd > windowStart && used + next > budget) {
                    break;
                }
                used += next;
                windowEnd++;
            }
            List<String> window = paragraphs.subList(windowStart, windowEnd);
            List<SemanticGroup> groups = window.size() < 3
                    ? List.of(new SemanticGroup(0, window.size() - 1, ""))
                    : callModel(section, window);
            validateCoverage(groups, window.size());
            int offset = windowStart;
            for (SemanticGroup group : groups) {
                out.add(new SemanticGroup(
                        group.startInclusive() + offset,
                        group.endInclusive() + offset,
                        group.topic()));
            }
            windowStart = windowEnd;
        }
        return out;
    }

    private void validateCoverage(List<SemanticGroup> groups, int size) {
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("empty semantic groups");
        }
        int next = 0;
        for (SemanticGroup group : groups) {
            if (group.startInclusive() != next
                    || group.endInclusive() < group.startInclusive()
                    || group.endInclusive() >= size) {
                throw new IllegalArgumentException("semantic groups are not contiguous");
            }
            next = group.endInclusive() + 1;
        }
        if (next != size) {
            throw new IllegalArgumentException("semantic groups do not cover all paragraphs");
        }
    }

    private String cleanJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        String value = raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return value;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return value;
        }
    }

    private long semanticCacheTtlMs() {
        return Math.max(1L, properties.getChunking().getSemanticCacheTtlMinutes()) * 60_000L;
    }

    private void putCache(String key, CacheEntry entry) {
        int maxEntries = Math.max(1, properties.getChunking().getSemanticCacheMaxEntries());
        evictExpiredEntries();
        if (cache.size() >= maxEntries) {
            cache.keySet().stream().findFirst().ifPresent(cache::remove);
        }
        cache.put(key, entry);
    }

    private void evictExpiredEntries() {
        cache.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    private record CacheEntry(List<SemanticGroup> groups, long expiresAtMs) {
        private boolean expired() {
            return System.currentTimeMillis() >= expiresAtMs;
        }
    }
}
