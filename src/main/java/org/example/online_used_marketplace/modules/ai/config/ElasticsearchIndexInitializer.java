package org.example.online_used_marketplace.modules.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeChunk;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeChunkMapper;
import org.example.online_used_marketplace.modules.ai.service.impl.KnowledgeChunkEsDoc;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 启动时初始化 ES 索引与 mapping（关键词检索链路保障）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchIndexInitializer {

    private final ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    @PostConstruct
    public void init() {
        ensureIndex("postConstruct", false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initAfterReady() {
        ensureIndex("applicationReady", true);
    }

    private void ensureIndex(String phase, boolean backfill) {
        ElasticsearchOperations elasticsearchOperations = elasticsearchOperationsProvider.getIfAvailable();
        if (elasticsearchOperations == null) {
            log.warn("[ES] index init skipped, no ElasticsearchOperations bean. phase={}", phase);
            return;
        }
        try {
            IndexOperations indexOps = elasticsearchOperations.indexOps(KnowledgeChunkEsDoc.class);
            if (!indexOps.exists()) {
                boolean created = indexOps.create();
                log.info("[ES] create index ai_knowledge_chunk: {}, phase={}", created, phase);
            }
            boolean mappingOk = indexOps.putMapping(indexOps.createMapping(KnowledgeChunkEsDoc.class));
            log.info("[ES] put mapping ai_knowledge_chunk: {}, phase={}", mappingOk, phase);
            if (backfill) {
                backfillExistingChunks(elasticsearchOperations);
            }
        } catch (Exception e) {
            log.warn("[ES] index init skipped/failed, keyword search will fallback. phase={}, reason={}", phase, e.getMessage());
        }
    }

    private void backfillExistingChunks(ElasticsearchOperations elasticsearchOperations) {
        try {
            List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
            if (chunks == null || chunks.isEmpty()) {
                log.info("[ES] chunk backfill skipped, no mysql chunks.");
                return;
            }
            int success = 0;
            for (KnowledgeChunk chunk : chunks) {
                if (chunk.getId() == null) {
                    continue;
                }
                elasticsearchOperations.save(toEsDoc(chunk));
                success++;
            }
            log.info("[ES] chunk backfill finished, total={}, success={}", chunks.size(), success);
        } catch (Exception ex) {
            log.warn("[ES] chunk backfill skipped/failed, reason={}", ex.getMessage());
        }
    }

    private KnowledgeChunkEsDoc toEsDoc(KnowledgeChunk chunk) {
        KnowledgeMetadata metadata = chunk.getMetadata();
        KnowledgeChunkEsDoc doc = new KnowledgeChunkEsDoc();
        doc.setId(String.valueOf(chunk.getId()));
        doc.setChunkId(chunk.getId());
        doc.setDocId(chunk.getDocId());
        doc.setContent(chunk.getContent());
        doc.setDocTitle(metadata == null ? "" : safe(metadata.getDocTitle()));
        doc.setSourceType(metadata == null ? "" : safe(metadata.getSourceType()));
        doc.setCategory(metadata == null ? "" : safe(metadata.getCategory()));
        doc.setRiskLevel(metadata == null ? "" : safe(metadata.getRiskLevel()));
        doc.setSourceUri(metadata == null ? "" : safe(metadata.getSourceUri()));
        doc.setSourceTrustLevel(metadata == null ? "" : safe(metadata.getSourceTrustLevel()));
        doc.setDocVersion(metadata == null ? "" : safe(metadata.getDocVersion()));
        doc.setEffectiveFrom(metadata == null ? "" : safe(metadata.getEffectiveFrom()));
        doc.setEffectiveTo(metadata == null ? "" : safe(metadata.getEffectiveTo()));
        doc.setDocStatus(metadata == null ? "" : safe(metadata.getDocStatus()));
        doc.setDisabledReason(metadata == null ? "" : safe(metadata.getDisabledReason()));
        doc.setIndexVersion(StringUtils.hasText(chunk.getIndexVersion())
                ? chunk.getIndexVersion()
                : metadata == null ? "" : safe(metadata.getIndexVersion()));
        doc.setLogicalKnowledgeId(metadata == null ? "" : safe(metadata.getLogicalKnowledgeId()));
        return doc;
    }

    private String safe(String text) {
        return StringUtils.hasText(text) ? text : "";
    }
}
