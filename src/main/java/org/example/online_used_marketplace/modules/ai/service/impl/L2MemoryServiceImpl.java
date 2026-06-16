package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.entity.L2MemoryRecord;

import org.example.online_used_marketplace.modules.ai.service.EmbeddingService;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class L2MemoryServiceImpl implements L2MemoryService {

    private static final double EMBEDDING_WEIGHT = 0.7D;
    private static final double IMPORTANCE_WEIGHT = 0.3D;
    private static final double DEFAULT_L2_IMPORTANCE = 0.3D;

    private final org.example.online_used_marketplace.modules.ai.mapper.l2.L2MemoryRecordMapper l2MemoryRecordMapper;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    @Override
    public void saveMemory(String userId, String sessionId, String memoryType, String content, Double importance) {
        saveMemory(userId, sessionId, memoryType, content, importance, "{}");
    }

    @Override
    public void saveMemory(String userId, String sessionId, String memoryType, String content, Double importance, String metadataJson) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        L2MemoryRecord record = new L2MemoryRecord();
        record.setUserId(StringUtils.hasText(userId) ? userId.trim() : "anonymous");
        record.setSessionId(StringUtils.hasText(sessionId) ? sessionId.trim() : "");
        record.setMemoryType(StringUtils.hasText(memoryType) ? memoryType.trim().toUpperCase(Locale.ROOT) : "FACT");
        record.setContent(content.trim());
        record.setImportance(normalize01(importance == null ? DEFAULT_L2_IMPORTANCE : importance));
        record.setEmbeddingJson(toEmbeddingJson(content.trim()));
        record.setMetadataJson(StringUtils.hasText(metadataJson) ? metadataJson.trim() : "{}");
        record.setCreatedTime(LocalDateTime.now());
        record.setUpdatedTime(LocalDateTime.now());
        l2MemoryRecordMapper.insert(record);
    }

    @Override
    public List<L2MemoryHitVO> retrieve(String userId, String sessionId, String query, int topK, double threshold) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        int safeTopK = topK <= 0 ? 5 : Math.min(topK, 20);
        double safeThreshold = Math.max(0D, threshold);

        LambdaQueryWrapper<L2MemoryRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(userId)) {
            wrapper.eq(L2MemoryRecord::getUserId, userId.trim());
        }
        wrapper.orderByDesc(L2MemoryRecord::getUpdatedTime);
        wrapper.last("limit 200");

        List<L2MemoryRecord> candidates = l2MemoryRecordMapper.selectList(wrapper);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<String> queryTokens = tokenize(query);
        float[] queryVector = embedQuery(query);
        List<L2MemoryHitVO> scored = new ArrayList<>();

        for (L2MemoryRecord one : candidates) {
            String text = one.getContent();
            if (!StringUtils.hasText(text)) {
                continue;
            }

            double lexicalScore = lexicalCoverageScore(queryTokens, text);
            double semanticScore = semanticScore(queryVector, one.getEmbeddingJson());
            double embeddingScore = semanticScore > 0D ? semanticScore : lexicalScore;
            double importanceScore = normalize01(one.getImportance());
            double score = EMBEDDING_WEIGHT * embeddingScore + IMPORTANCE_WEIGHT * importanceScore;

            double tfScore = tfBagScore(queryTokens, text);
            boolean passThreshold = score >= safeThreshold;
            boolean passTfFallback = tfScore >= 0.35D;
            if (!passThreshold && !passTfFallback) {
                continue;
            }

            L2MemoryHitVO hit = new L2MemoryHitVO();
            hit.setId(one.getId());
            hit.setUserId(one.getUserId());
            hit.setSessionId(one.getSessionId());
            hit.setMemoryType(one.getMemoryType());
            hit.setContent(one.getContent());
            hit.setMetadataJson(one.getMetadataJson());
            hit.setEmbeddingScore(embeddingScore);
            hit.setImportanceScore(importanceScore);
            hit.setTfScore(tfScore);
            hit.setScore(Math.max(score, tfScore));
            scored.add(hit);
        }

        scored.sort(Comparator.comparing(L2MemoryHitVO::getScore, Comparator.nullsLast(Comparator.reverseOrder())));
        return scored.stream().limit(safeTopK).collect(Collectors.toList());
    }

    private double lexicalCoverageScore(List<String> queryTokens, String text) {
        if (queryTokens.isEmpty() || !StringUtils.hasText(text)) {
            return 0D;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        long hit = queryTokens.stream().filter(lower::contains).count();
        return hit * 1.0D / queryTokens.size();
    }

    private String toEmbeddingJson(String content) {
        try {
            List<float[]> vectors = embeddingService.embed(List.of(content));
            if (vectors == null || vectors.isEmpty()) {
                return "[]";
            }
            List<Double> values = new ArrayList<>();
            for (float value : vectors.get(0)) {
                values.add((double) value);
            }
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            log.warn("L2 memory embedding failed, fallback to lexical recall. reason={}", ex.getMessage());
            return "[]";
        }
    }

    private float[] embedQuery(String query) {
        try {
            List<float[]> vectors = embeddingService.embed(List.of(query));
            if (vectors == null || vectors.isEmpty()) {
                return new float[0];
            }
            return vectors.get(0);
        } catch (Exception ex) {
            log.warn("L2 query embedding failed, fallback to lexical recall. reason={}", ex.getMessage());
            return new float[0];
        }
    }

    private double semanticScore(float[] queryVector, String embeddingJson) {
        if (queryVector == null || queryVector.length == 0 || !StringUtils.hasText(embeddingJson) || "[]".equals(embeddingJson.trim())) {
            return 0D;
        }
        try {
            List<Double> values = objectMapper.readValue(embeddingJson, new TypeReference<List<Double>>() {});
            if (values == null || values.isEmpty() || values.size() != queryVector.length) {
                return 0D;
            }
            double dot = 0D;
            double qNorm = 0D;
            double mNorm = 0D;
            for (int i = 0; i < queryVector.length; i++) {
                double q = queryVector[i];
                double m = values.get(i);
                dot += q * m;
                qNorm += q * q;
                mNorm += m * m;
            }
            if (qNorm <= 0D || mNorm <= 0D) {
                return 0D;
            }
            double cosine = dot / (Math.sqrt(qNorm) * Math.sqrt(mNorm));
            return normalize01((cosine + 1D) / 2D);
        } catch (Exception ex) {
            return 0D;
        }
    }

    private double tfBagScore(List<String> queryTokens, String text) {
        if (queryTokens.isEmpty() || !StringUtils.hasText(text)) {
            return 0D;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        double total = 0D;
        for (String token : queryTokens) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            int freq = countContains(lower, token);
            total += Math.min(freq, 3);
        }
        return Math.min(total / (queryTokens.size() * 2D), 1.0D);
    }

    private int countContains(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }

    private List<String> tokenize(String q) {
        Set<String> set = java.util.Arrays.stream(q.toLowerCase(Locale.ROOT)
                        .split("\\s+|，|。|,|;|；|:|：|/|\\\\|\\(|\\)|\\[|\\]|\"|'"))
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(s -> s.length() >= 2)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return new ArrayList<>(set);
    }

    private double normalize01(Double value) {
        if (value == null) {
            return 0D;
        }
        if (value < 0D) {
            return 0D;
        }
        return Math.min(value, 1.0D);
    }
}
