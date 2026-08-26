package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeChunk;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeChunkMapper;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeDocMapper;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetiredVersionCleanupServiceImplTest {

    @SuppressWarnings("unchecked")
    @Test
    void expiredRetiredRevisionDeletesOnlyChunkArtifacts() {
        KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<ElasticsearchOperations> esProvider = mock(ObjectProvider.class);
        ElasticsearchOperations esOperations = mock(ElasticsearchOperations.class);
        IndexOperations indexOperations = mock(IndexOperations.class);
        when(esProvider.getIfAvailable()).thenReturn(esOperations);
        when(esOperations.indexOps(KnowledgeChunkEsDoc.class)).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);

        KnowledgeDoc expired = new KnowledgeDoc();
        expired.setId(41L);
        expired.setStatus(KnowledgeDocStatus.RETIRED.name());
        expired.setRetentionUntil(LocalDateTime.now().minusMinutes(1));
        expired.setCleanupStatus("PENDING");
        when(docMapper.selectList(any(Wrapper.class))).thenReturn(List.of(expired));
        when(docMapper.update(any(KnowledgeDoc.class), any(Wrapper.class))).thenReturn(1);

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(88L);
        chunk.setDocId(41L);
        when(chunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(chunk));

        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getLifecycle().setCleanupEnabled(true);
        properties.getLifecycle().setCleanupBatchSize(1);
        KnowledgeRetiredVersionCleanupServiceImpl service = new KnowledgeRetiredVersionCleanupServiceImpl(
                docMapper, chunkMapper, vectorStore, esProvider, properties, new SimpleMeterRegistry());

        assertEquals(1, service.cleanupExpiredRetiredVersions());
        verify(vectorStore).deleteByChunkIds(List.of(88L));
        verify(esOperations).delete(anyString(), any(IndexCoordinates.class));
        verify(chunkMapper).hardDeleteByDocId(41L);
    }
}
