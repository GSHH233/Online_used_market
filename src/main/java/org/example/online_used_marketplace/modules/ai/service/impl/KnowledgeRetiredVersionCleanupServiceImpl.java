package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeChunk;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeChunkMapper;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeDocMapper;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeRetiredVersionCleanupService;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Keeps source documents for audit/rebuild, while removing only expired chunk artifacts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetiredVersionCleanupServiceImpl implements KnowledgeRetiredVersionCleanupService {

    private static final String ES_INDEX_NAME = "ai_knowledge_chunk";

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final VectorStore vectorStore;
    private final ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;
    private final KnowledgeIngestionProperties properties;
    private final MeterRegistry meterRegistry;

    @Override
    public int cleanupExpiredRetiredVersions() {
        if (!properties.getLifecycle().isCleanupEnabled()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int batchSize = Math.max(1, Math.min(properties.getLifecycle().getCleanupBatchSize(), 200));
        QueryWrapper<KnowledgeDoc> query = new QueryWrapper<>();
        query.eq("status", KnowledgeDocStatus.RETIRED.name())
                .le("retention_until", now)
                .and(wrapper -> wrapper.isNull("cleanup_status")
                        .or()
                        .in("cleanup_status", List.of("PENDING", "FAILED")))
                .orderByAsc("retention_until", "id")
                .last("limit " + batchSize);
        List<KnowledgeDoc> candidates = knowledgeDocMapper.selectList(query);
        int cleaned = 0;
        for (KnowledgeDoc candidate : candidates) {
            if (candidate == null || candidate.getId() == null || !claim(candidate.getId(), now)) {
                continue;
            }
            try {
                purgeChunkArtifacts(candidate.getId());
                markCompleted(candidate.getId());
                meterRegistry.counter("rag_knowledge_retired_cleanup_total", "status", "success").increment();
                cleaned++;
            } catch (Exception ex) {
                markFailed(candidate.getId(), errorMessage(ex));
                meterRegistry.counter("rag_knowledge_retired_cleanup_total", "status", "failed").increment();
                log.warn("[KnowledgeLifecycle] retired version cleanup failed, docId={}, reason={}",
                        candidate.getId(), errorMessage(ex));
            }
        }
        if (!candidates.isEmpty()) {
            log.info("[KnowledgeLifecycle] retired cleanup scan finished, candidates={}, cleaned={}",
                    candidates.size(), cleaned);
        }
        return cleaned;
    }

    private boolean claim(Long docId, LocalDateTime now) {
        KnowledgeDoc patch = new KnowledgeDoc();
        patch.setCleanupStatus("RUNNING");
        patch.setCleanupError(null);
        patch.setUpdatedTime(now);
        return knowledgeDocMapper.update(patch, new LambdaUpdateWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getId, docId)
                .eq(KnowledgeDoc::getStatus, KnowledgeDocStatus.RETIRED.name())
                .le(KnowledgeDoc::getRetentionUntil, now)
                .and(wrapper -> wrapper.isNull(KnowledgeDoc::getCleanupStatus)
                        .or()
                        .in(KnowledgeDoc::getCleanupStatus, List.of("PENDING", "FAILED")))) > 0;
    }

    private void purgeChunkArtifacts(Long docId) {
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getDocId, docId)
                .orderByAsc(KnowledgeChunk::getId));
        List<Long> chunkIds = chunks.stream()
                .map(KnowledgeChunk::getId)
                .filter(id -> id != null && id > 0)
                .toList();
        if (chunkIds.isEmpty()) {
            return;
        }
        vectorStore.deleteByChunkIds(chunkIds);
        deleteEsByChunkIds(chunkIds);
        knowledgeChunkMapper.hardDeleteByDocId(docId);
    }

    private void deleteEsByChunkIds(List<Long> chunkIds) {
        ElasticsearchOperations operations = elasticsearchOperationsProvider.getIfAvailable();
        if (operations == null) {
            throw new IllegalStateException("ElasticsearchOperations is unavailable");
        }
        IndexOperations indexOperations = operations.indexOps(KnowledgeChunkEsDoc.class);
        if (!indexOperations.exists()) {
            return;
        }
        for (Long chunkId : chunkIds) {
            operations.delete(String.valueOf(chunkId), IndexCoordinates.of(ES_INDEX_NAME));
        }
        indexOperations.refresh();
    }

    private void markCompleted(Long docId) {
        KnowledgeDoc patch = new KnowledgeDoc();
        patch.setCleanupStatus("COMPLETED");
        patch.setCleanupError(null);
        patch.setCleanedAt(LocalDateTime.now());
        patch.setUpdatedTime(LocalDateTime.now());
        knowledgeDocMapper.update(patch, new LambdaUpdateWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getId, docId)
                .eq(KnowledgeDoc::getStatus, KnowledgeDocStatus.RETIRED.name())
                .eq(KnowledgeDoc::getCleanupStatus, "RUNNING"));
    }

    private void markFailed(Long docId, String reason) {
        KnowledgeDoc patch = new KnowledgeDoc();
        patch.setCleanupStatus("FAILED");
        patch.setCleanupError(reason);
        patch.setUpdatedTime(LocalDateTime.now());
        knowledgeDocMapper.update(patch, new LambdaUpdateWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getId, docId)
                .eq(KnowledgeDoc::getStatus, KnowledgeDocStatus.RETIRED.name())
                .eq(KnowledgeDoc::getCleanupStatus, "RUNNING"));
    }

    private String errorMessage(Exception ex) {
        String message = ex == null ? "unknown error" : ex.getMessage();
        if (!StringUtils.hasText(message)) {
            message = ex == null ? "unknown error" : ex.getClass().getSimpleName();
        }
        return message.substring(0, Math.min(500, message.length()));
    }
}
