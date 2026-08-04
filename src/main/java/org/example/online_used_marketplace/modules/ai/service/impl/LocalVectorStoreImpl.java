package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeMetadata;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.example.online_used_marketplace.modules.ai.vo.VectorRecordVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地向量存储占位实现（MVP）
 */
@Service
public class LocalVectorStoreImpl implements VectorStore {

    private static final int DEFAULT_TOP_K = 5;

    private final Map<String, VectorRecordVO> store = new ConcurrentHashMap<>();

    @Override
    public void upsert(List<VectorRecordVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (VectorRecordVO record : records) {
            if (record == null) {
                continue;
            }
            if (record.getDocId() == null || record.getChunkId() == null) {
                continue;
            }
            if (record.getVector() == null || record.getVector().length == 0) {
                continue;
            }
            if (!StringUtils.hasText(record.getContent())) {
                continue;
            }
            String key = buildKey(record.getDocId(), record.getChunkId());
            store.put(key, record);
        }
    }

    @Override
    public List<RagSourceVO> search(float[] queryVector, int topK, Map<String, Object> filters) {
        if (queryVector == null || queryVector.length == 0) {
            return Collections.emptyList();
        }

        int k = (topK <= 0 ? DEFAULT_TOP_K : topK);
        List<RagSourceVO> candidates = new ArrayList<>();

        for (VectorRecordVO record : store.values()) {
            if (record == null) {
                continue;
            }
            if (!matchFilters(record, filters)) {
                continue;
            }
            if (record.getVector() == null || record.getVector().length != queryVector.length) {
                continue;
            }

            double score = cosineSimilarity(queryVector, record.getVector());
            KnowledgeMetadata meta=record.getMetadata();
            RagSourceVO source = new RagSourceVO();
            source.setDocId(record.getDocId());
            source.setChunkId(record.getChunkId());
            source.setSnippet(buildSnippet(record.getContent()));
            source.setScore(score);
            source.setDocTitle(meta != null ? meta.getDocTitle() : "");
            source.setSourceType(meta != null ? meta.getSourceType() : null);
            source.setSourceUri(meta != null ? meta.getSourceUri() : null);
            source.setSourceTrustLevel(meta != null ? meta.getSourceTrustLevel() : null);
            source.setDocVersion(meta != null ? meta.getDocVersion() : null);
            source.setEffectiveFrom(meta != null ? meta.getEffectiveFrom() : null);
            source.setEffectiveTo(meta != null ? meta.getEffectiveTo() : null);
            source.setDocStatus(meta != null ? meta.getDocStatus() : null);
            source.setDisabledReason(meta != null ? meta.getDisabledReason() : null);
            source.setIndexVersion(meta != null ? meta.getIndexVersion() : null);
            candidates.add(source);
        }

        candidates.sort(Comparator.comparing(RagSourceVO::getScore, Comparator.nullsLast(Comparator.reverseOrder())));
        return candidates.stream().limit(k).toList();
    }

    @Override
    public void deleteByDocId(Long docId) {
        if (docId == null) {
            return;
        }
        String prefix = docId + "_";
        store.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix));
    }

    @Override
    public void deleteByChunkIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        Set<Long> ids = new HashSet<>(chunkIds);
        store.entrySet().removeIf(entry -> {
            VectorRecordVO record = entry.getValue();
            return record != null && ids.contains(record.getChunkId());
        });
    }

    @Override
    public List<Map<String, Object>> debugQueryByDocId(Long docId, Integer limit) {
        return null;
    }

    private String buildKey(Long docId, Long chunkId) {
        return docId + "_" + chunkId;
    }

    private boolean matchFilters(VectorRecordVO record, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }

        KnowledgeMetadata meta=record.getMetadata();
        if (meta == null) {
            return false;
        }
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object value = filter.getValue();
            if (value == null) {
                continue;
            }

            String key = filter.getKey();
            if ("sourceType".equals(key) && !equalsIgnoreCase(meta.getSourceType(), value)) {
                return false;
            }
            if ("category".equals(key) && !equalsIgnoreCase(meta.getCategory(),value)) {
                return false;
            }
            if ("riskLevel".equals(key) && !equalsIgnoreCase(meta.getRiskLevel(), value)) {
                return false;
            }
        }
        return true;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return -1;
        }
        float dot = 0;
        float na = 0;
        float nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return -1;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-8);
    }

    private String buildSnippet(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String text = content.trim();
        return text.length() <= 240 ? text : text.substring(0, 240);
    }


    private boolean equalsIgnoreCase(Object left, Object right) {
        String l = asString(left);
        String r = asString(right);
        if (!StringUtils.hasText(l) || !StringUtils.hasText(r)) {
            return false;
        }
        return l.trim().equalsIgnoreCase(r.trim());
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
