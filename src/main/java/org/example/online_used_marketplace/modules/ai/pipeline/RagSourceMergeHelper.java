package org.example.online_used_marketplace.modules.ai.pipeline;

import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchHitVO;
import org.example.online_used_marketplace.modules.ai.vo.RagSourceVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG来源映射与去重合并工具
 */
@Component
public class RagSourceMergeHelper {

    public List<RagSourceVO> toRagSources(List<KnowledgeSearchHitVO> hits) {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        List<RagSourceVO> sources = new ArrayList<>();
        for (KnowledgeSearchHitVO hit : hits) {
            if (hit == null) {
                continue;
            }
            RagSourceVO vo = new RagSourceVO();
            vo.setDocId(hit.getDocId());
            vo.setDocTitle(hit.getDocTitle());
            vo.setChunkId(hit.getChunkId());
            vo.setSnippet(hit.getSnippet());
            vo.setScore(hit.getScore());
            vo.setSourceType(hit.getSourceType());
            vo.setSourceUri(hit.getSourceUri());
            vo.setSourceTrustLevel(hit.getSourceTrustLevel());
            vo.setDocVersion(hit.getDocVersion());
            vo.setEffectiveFrom(hit.getEffectiveFrom());
            vo.setEffectiveTo(hit.getEffectiveTo());
            vo.setDocStatus(hit.getDocStatus());
            vo.setDisabledReason(hit.getDisabledReason());
            vo.setLogicalKnowledgeId(hit.getLogicalKnowledgeId());
            vo.setRetrievalScore(hit.getRetrievalScore());
            vo.setRerankScore(hit.getRerankScore());
            vo.setRerankReason(hit.getRerankReason());
            sources.add(vo);
        }
        return sources;
    }

    public List<RagSourceVO> mergeSources(List<RagSourceVO> existing, List<RagSourceVO> incoming, int limit) {
        return mergeSources(existing, incoming, limit, 2);
    }

    public List<RagSourceVO> mergeSources(List<RagSourceVO> existing, List<RagSourceVO> incoming, int limit, int reservedPerType) {
        Map<String, RagSourceVO> dedupMap = new LinkedHashMap<>();

        if (existing != null) {
            for (RagSourceVO source : existing) {
                if (source == null) {
                    continue;
                }
                dedupMap.put(sourceKey(source), source);
            }
        }

        if (incoming != null) {
            for (RagSourceVO source : incoming) {
                if (source == null) {
                    continue;
                }
                dedupMap.putIfAbsent(sourceKey(source), source);
            }
        }

        List<RagSourceVO> all = new ArrayList<>(dedupMap.values());
        if (all.size() <= limit) {
            return all;
        }

        // Sort by score descending globally
        all.sort((a, b) -> {
            double sa = a.getScore() != null ? a.getScore() : 0d;
            double sb = b.getScore() != null ? b.getScore() : 0d;
            return Double.compare(sb, sa);
        });

        // Track remaining reserved slots per sourceType
        Map<String, Integer> remainingSlots = new HashMap<>();
        for (RagSourceVO item : all) {
            remainingSlots.putIfAbsent(item.getSourceType(), reservedPerType);
        }

        List<RagSourceVO> guaranteed = new ArrayList<>();
        List<RagSourceVO> overflow = new ArrayList<>();

        for (RagSourceVO item : all) {
            String type = item.getSourceType();
            Integer slots = remainingSlots.get(type);
            if (slots != null && slots > 0) {
                guaranteed.add(item);
                remainingSlots.put(type, slots - 1);
            } else {
                overflow.add(item);
            }
        }

        // If guaranteed items already fill or exceed the limit, take the top ones
        if (guaranteed.size() >= limit) {
            return guaranteed.subList(0, limit);
        }

        // Fill remaining slots from overflow pool
        int slotsLeft = limit - guaranteed.size();
        guaranteed.addAll(overflow.subList(0, Math.min(slotsLeft, overflow.size())));
        return guaranteed;
    }

    private String sourceKey(RagSourceVO source) {
        return String.valueOf(source.getDocId()) + "_" + String.valueOf(source.getChunkId());
    }
}
