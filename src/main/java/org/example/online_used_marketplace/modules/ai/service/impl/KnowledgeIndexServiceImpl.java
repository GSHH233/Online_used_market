package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeRepairBatchRequestDTO;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeChunk;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeIndexTask;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStage;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskType;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeChunkMapper;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeDocMapper;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeIndexTaskMapper;
import org.example.online_used_marketplace.modules.ai.messaging.KnowledgeIndexTaskMessage;
import org.example.online_used_marketplace.modules.ai.messaging.KnowledgeIndexTaskPublisher;
import org.example.online_used_marketplace.modules.ai.service.ChunkingService;
import org.example.online_used_marketplace.modules.ai.service.ChunkQualityValidator;
import org.example.online_used_marketplace.modules.ai.service.EmbeddingService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeDocService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeIndexService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeStorageConsistencyService;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.vo.ChunkPayloadVO;
import org.example.online_used_marketplace.modules.ai.vo.ChunkQualityReportVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeIndexTaskVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeMetadata;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeRepairBatchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeReindexTriggerVO;
import org.example.online_used_marketplace.modules.ai.vo.StorageConsistencyDocVO;
import org.example.online_used_marketplace.modules.ai.vo.VectorRecordVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识入库、重建和修复任务服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeIndexServiceImpl implements KnowledgeIndexService {

    private static final int DEFAULT_CHUNK_SIZE = 600;
    private static final int DEFAULT_OVERLAP = 100;
    private static final int DEFAULT_CONSISTENCY_LIMIT = 10_000;
    private static final int VERIFY_MAX_ATTEMPTS = 6;
    private static final long VERIFY_RETRY_INTERVAL_MS = 350L;
    private static final String EMBEDDING_MODEL_NAME = "spring-ai-openai-text-embedding";
    private static final String ES_INDEX_NAME = "ai_knowledge_chunk";

    private final KnowledgeDocService knowledgeDocService;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeIndexTaskMapper knowledgeIndexTaskMapper;
    private final ChunkingService chunkingService;
    private final ChunkQualityValidator chunkQualityValidator;
    private final KnowledgeIngestionProperties ingestionProperties;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;
    private final KnowledgeStorageConsistencyService consistencyService;
    private final ObjectProvider<KnowledgeIndexTaskPublisher> taskPublisherProvider;
    private final MeterRegistry meterRegistry;

    @Override
    public KnowledgeReindexTriggerVO triggerReindex(Long docId) {
        KnowledgeIndexTask task = createTask(docId, KnowledgeIndexTaskType.REINDEX, false, true);
        executeTask(task.getId(), true);
        KnowledgeIndexTask latest = requireTask(task.getId());
        if (KnowledgeIndexTaskStatus.FAILED.equals(latest.getStatus())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "更新索引失败: " + latest.getErrorMsg());
        }
        return triggerVO(latest, "重建索引完成");
    }

    @Override
    public KnowledgeReindexTriggerVO triggerIngest(Long docId, Boolean async) {
        boolean asyncTask = Boolean.TRUE.equals(async);
        KnowledgeIndexTask task = createTask(docId, KnowledgeIndexTaskType.INGEST, asyncTask, true);
        dispatch(task.getId(), asyncTask, false);
        KnowledgeIndexTask latest = requireTask(task.getId());
        return triggerVO(latest, taskMessage(latest, "入库任务已提交", "入库任务已完成", "入库任务失败"));
    }

    @Override
    public KnowledgeReindexTriggerVO repairDoc(Long docId, Boolean forceReindex, Boolean async) {
        boolean asyncTask = Boolean.TRUE.equals(async);
        boolean force = Boolean.TRUE.equals(forceReindex);
        KnowledgeIndexTask task = createTask(docId, KnowledgeIndexTaskType.REPAIR, asyncTask, force);
        dispatch(task.getId(), asyncTask, false);
        KnowledgeIndexTask latest = requireTask(task.getId());
        return triggerVO(latest, taskMessage(latest, "修复任务已提交", "修复任务已完成", "修复任务失败"));
    }

    @Override
    public KnowledgeReindexTriggerVO rollbackRevision(Long docId, Boolean async) {
        boolean asyncTask = Boolean.TRUE.equals(async);
        knowledgeDocService.reserveRetiredRevisionForRollback(docId);
        try {
            KnowledgeIndexTask task = createTask(docId, KnowledgeIndexTaskType.ROLLBACK, asyncTask, true);
            dispatch(task.getId(), asyncTask, false);
            KnowledgeIndexTask latest = requireTask(task.getId());
            if (KnowledgeIndexTaskStatus.FAILED.equals(latest.getStatus())
                    || KnowledgeIndexTaskStatus.DEAD_LETTERED.equals(latest.getStatus())) {
                knowledgeDocService.releaseRollbackReservation(docId, latest.getErrorMsg());
            }
            return triggerVO(latest, taskMessage(latest, "回滚任务已提交", "知识版本回滚完成", "知识版本回滚失败"));
        } catch (RuntimeException ex) {
            knowledgeDocService.releaseRollbackReservation(docId, safeMsg(ex));
            throw ex;
        }
    }

    @Override
    public KnowledgeRepairBatchResponseVO repairBatch(KnowledgeRepairBatchRequestDTO dto) {
        KnowledgeRepairBatchRequestDTO request = dto == null ? new KnowledgeRepairBatchRequestDTO() : dto;
        boolean force = Boolean.TRUE.equals(request.getForceReindex());
        boolean async = Boolean.TRUE.equals(request.getAsync());
        int perDocLimit = request.getPerDocLimit() == null ? DEFAULT_CONSISTENCY_LIMIT : request.getPerDocLimit();
        List<Long> docIds = consistencyService.resolveDocIds(null, request.getSampleDocs());
        List<KnowledgeIndexTaskVO> tasks = new ArrayList<>();
        List<StorageConsistencyDocVO> checkedDocsDetail = new ArrayList<>();
        List<StorageConsistencyDocVO> skippedDocs = new ArrayList<>();
        int skipped = 0;

        for (Long docId : docIds) {
            StorageConsistencyDocVO before = consistencyService.inspectDoc(docId, perDocLimit);
            checkedDocsDetail.add(before);
            if (!force && Boolean.TRUE.equals(before.getConsistent())) {
                skipped++;
                skippedDocs.add(before);
                continue;
            }
            KnowledgeIndexTask task = createTask(docId, KnowledgeIndexTaskType.REPAIR, async, force);
            dispatch(task.getId(), async, false);
            tasks.add(toVO(requireTask(task.getId())));
        }

        KnowledgeRepairBatchResponseVO response = new KnowledgeRepairBatchResponseVO();
        response.setCheckedDocs(docIds.size());
        response.setSubmittedTasks(tasks.size());
        response.setSkippedConsistentDocs(skipped);
        response.setCheckedDocsDetail(checkedDocsDetail);
        response.setSkippedDocs(skippedDocs);
        response.setTasks(tasks);
        return response;
    }

    @Override
    public KnowledgeIndexTaskVO getTask(Long taskId) {
        return toVO(requireTask(taskId));
    }

    @Override
    public List<KnowledgeIndexTaskVO> listTasks(Long docId,
                                                KnowledgeIndexTaskStatus status,
                                                KnowledgeIndexTaskType taskType,
                                                Integer limit) {
        QueryWrapper<KnowledgeIndexTask> wrapper = new QueryWrapper<>();
        if (docId != null && docId > 0) {
            wrapper.eq("doc_id", docId);
        }
        if (status != null) {
            wrapper.eq("status", status.name());
        }
        if (taskType != null) {
            wrapper.eq("task_type", taskType.name());
        }
        wrapper.orderByDesc("id");
        wrapper.last("limit " + Math.max(1, Math.min(limit == null ? 20 : limit, 100)));
        return knowledgeIndexTaskMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .toList();
    }

    private KnowledgeIndexTask createTask(Long docId,
                                          KnowledgeIndexTaskType taskType,
                                          boolean async,
                                          boolean forceRepair) {
        validateDoc(docId, taskType);
        LocalDateTime now = LocalDateTime.now();
        KnowledgeIndexTask task = new KnowledgeIndexTask();
        task.setDocId(docId);
        task.setTaskType(taskType);
        task.setStatus(KnowledgeIndexTaskStatus.PENDING);
        task.setStage(KnowledgeIndexTaskStage.CREATED);
        task.setAsyncTask(async);
        task.setForceRepair(forceRepair);
        task.setRetryCount(0);
        task.setChunkCount(0);
        task.setDbCount(0);
        task.setEsCount(0);
        task.setMilvusCount(0);
        task.setConsistent(false);
        task.setCreatedTime(now);
        task.setUpdatedTime(now);
        int insert = knowledgeIndexTaskMapper.insert(task);
        if (insert <= 0) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "创建知识入库任务失败");
        }
        return task;
    }

    private void dispatch(Long taskId, boolean async, boolean throwOnFailure) {
        if (async) {
            KnowledgeIndexTaskPublisher publisher = taskPublisherProvider.getIfAvailable();
            if (publisher == null) {
                LocalDateTime now = LocalDateTime.now();
                updateTask(taskId, patch -> {
                    patch.setStatus(KnowledgeIndexTaskStatus.FAILED);
                    patch.setStage(KnowledgeIndexTaskStage.FINISHED);
                    patch.setErrorMsg("RabbitMQ ingestion is disabled; use async=false or enable ai.knowledge.ingestion.rabbit");
                    patch.setFinishedAt(now);
                    patch.setUpdatedTime(now);
                });
                log.warn("[KnowledgeIndexMQ] async dispatch rejected because publisher is unavailable, taskId={}", taskId);
                return;
            }
            if (!markTaskQueued(taskId, 0)) {
                log.warn("[KnowledgeIndexMQ] task could not enter QUEUED state, taskId={}", taskId);
                return;
            }
            boolean published = publisher.publishInitial(KnowledgeIndexTaskMessage.initial(taskId));
            if (!published) {
                log.warn("[KnowledgeIndexMQ] initial publish failed; queued task will be recovered, taskId={}", taskId);
            }
            return;
        }
        executeTask(taskId, throwOnFailure);
    }

    private void executeTask(Long taskId, boolean throwOnFailure) {
        if (!claimTask(taskId, 0)) {
            KnowledgeIndexTask latest = requireTask(taskId);
            if (KnowledgeIndexTaskStatus.SUCCESS.equals(latest.getStatus())) {
                return;
            }
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "Knowledge index task cannot be claimed, status=" + latest.getStatus());
        }
        executeClaimedTask(taskId, throwOnFailure);
    }

    @Override
    public void executeQueuedTask(Long taskId, int retryCount) {
        if (!claimTask(taskId, retryCount)) {
            KnowledgeIndexTask latest = requireTask(taskId);
            if (KnowledgeIndexTaskStatus.SUCCESS.equals(latest.getStatus())
                    || KnowledgeIndexTaskStatus.RUNNING.equals(latest.getStatus())
                    || KnowledgeIndexTaskStatus.DEAD_LETTERED.equals(latest.getStatus())
                    || (latest.getRetryCount() != null && latest.getRetryCount() > retryCount)) {
                log.info("[KnowledgeIndexMQ] duplicate delivery skipped, taskId={}, retryCount={}, status={}",
                        taskId, retryCount, latest.getStatus());
                return;
            }
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "Knowledge index task cannot be claimed, status=" + latest.getStatus()
                            + ", storedRetryCount=" + latest.getRetryCount()
                            + ", messageRetryCount=" + retryCount);
        }
        executeClaimedTask(taskId, true);
    }

    @Override
    public boolean markTaskQueued(Long taskId, int retryCount) {
        KnowledgeIndexTask task = requireTask(taskId);
        if (KnowledgeIndexTaskStatus.QUEUED.equals(task.getStatus())
                && task.getRetryCount() != null
                && task.getRetryCount() == retryCount) {
            return true;
        }
        return knowledgeIndexTaskMapper.markQueued(taskId, retryCount) > 0;
    }

    @Override
    public void markTaskDeadLettered(Long taskId, int retryCount) {
        knowledgeIndexTaskMapper.markDeadLettered(taskId, retryCount);
        KnowledgeIndexTask task = requireTask(taskId);
        if (KnowledgeIndexTaskType.ROLLBACK.equals(task.getTaskType())) {
            knowledgeDocService.releaseRollbackReservation(task.getDocId(), "回滚任务进入死信队列");
        }
    }

    private boolean claimTask(Long taskId, int retryCount) {
        return knowledgeIndexTaskMapper.claimForExecution(taskId, Math.max(0, retryCount)) > 0;
    }

    private void executeClaimedTask(Long taskId, boolean throwOnFailure) {
        KnowledgeIndexTask task = requireTask(taskId);

        try {
            KnowledgeDoc doc = validateDoc(task.getDocId(), task.getTaskType());
            updateStage(taskId, KnowledgeIndexTaskStage.CONSISTENCY_CHECK);
            StorageConsistencyDocVO before = consistencyService.inspectDoc(task.getDocId(), DEFAULT_CONSISTENCY_LIMIT);
            if (KnowledgeIndexTaskType.REPAIR.equals(task.getTaskType())
                    && !Boolean.TRUE.equals(task.getForceRepair())
                    && Boolean.TRUE.equals(before.getConsistent())) {
                finishSuccess(taskId, before, 0, Map.of("action", "skip", "reason", "storage_already_consistent"));
                return;
            }

            updateStage(taskId, KnowledgeIndexTaskStage.STAGING);
            IndexResult result = rebuildIndexes(taskId, doc, before);
            VerifySnapshot verified = waitForConsistency(task.getDocId(), DEFAULT_CONSISTENCY_LIMIT);
            StorageConsistencyDocVO after = verified.consistency();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("before", before);
            detail.put("after", after);
            detail.put("verifyAttempts", verified.attempts());
            detail.put("insertedChunks", result.insertedChunks());
            detail.put("esUpserted", result.esUpserted());
            detail.put("indexVersion", result.indexVersion());
            detail.put("chunkQuality", result.quality());
            detail.put("taskType", task.getTaskType().name());
            if (KnowledgeIndexTaskType.ROLLBACK.equals(task.getTaskType())) {
                detail.put("rollbackRevision", doc.getRevision());
                detail.put("rollbackLogicalKnowledgeId", doc.getLogicalKnowledgeId());
            }

            if (!Boolean.TRUE.equals(after.getConsistent())) {
                failTask(taskId, "三存储一致性校验失败", detail);
                if (KnowledgeIndexTaskType.ROLLBACK.equals(task.getTaskType())) {
                    knowledgeDocService.releaseRollbackReservation(task.getDocId(), "回滚三存储一致性校验失败");
                }
                if (throwOnFailure) {
                    throw new BusinessException(ResultCode.INTERNAL_ERROR, "三存储一致性校验失败");
                }
                return;
            }
            finishSuccess(taskId, after, result.insertedChunks(), detail);
        } catch (Exception e) {
            log.error("[KnowledgeIndexJob] failed, taskId={}", taskId, e);
            failTask(taskId, safeMsg(e), Map.of("exception", e.getClass().getSimpleName()));
            if (KnowledgeIndexTaskType.ROLLBACK.equals(task.getTaskType())) {
                knowledgeDocService.releaseRollbackReservation(task.getDocId(), safeMsg(e));
            }
            if (throwOnFailure) {
                throw e instanceof BusinessException be
                        ? be
                        : new BusinessException(ResultCode.INTERNAL_ERROR, "知识入库任务失败: " + safeMsg(e));
            }
        }
    }

    private IndexResult rebuildIndexes(Long taskId, KnowledgeDoc doc, StorageConsistencyDocVO before) {
        Long docId = doc.getId();
        String indexVersion = "idx-" + docId + "-" + taskId;
        List<KnowledgeChunk> oldChunks = selectChunksByDoc(docId);
        cleanupVersionBestEffort(docId, indexVersion);

        updateStage(taskId, KnowledgeIndexTaskStage.SPLIT);
        List<ChunkPayloadVO> chunkPayloads = chunkingService.split(doc, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
        ChunkQualityReportVO quality = chunkQualityValidator.validate(
                doc, chunkPayloads,
                ingestionProperties.getChunking().getMinTokens(),
                ingestionProperties.getChunking().getMaxTokens());
        if (!quality.isValid()) {
            meterRegistry.counter("rag_ingest_consistency_total", "status", "chunk_quality_rejected")
                    .increment();
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "Chunk quality validation failed: " + String.join(",", quality.getViolations()));
        }
        meterRegistry.summary("rag_chunk_count", "strategy",
                        ingestionProperties.getChunking().getStrategyVersion())
                .record(quality.getChunkCount());
        meterRegistry.summary("rag_chunk_tokens", "statistic", "document_max")
                .record(quality.getTokenMax());

        List<KnowledgeChunk> insertedChunks = new ArrayList<>();
        boolean activated = false;
        try {
            updateStage(taskId, KnowledgeIndexTaskStage.DB_WRITE);
            for (ChunkPayloadVO payload : chunkPayloads) {
                if (!StringUtils.hasText(payload.getContent())) {
                    continue;
                }
                LocalDateTime now = LocalDateTime.now();
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocId(docId);
                chunk.setChunkIndex(payload.getChunkIndex());
                chunk.setContent(payload.getContent());
                chunk.setTokenCount(payload.getTokenCount());
                chunk.setIndexVersion(indexVersion);
                chunk.setIndexStatus("STAGING");
                KnowledgeMetadata metadata = buildMetadata(doc, payload);
                metadata.setIndexVersion(indexVersion);
                chunk.setMetadata(metadata);
                chunk.setCreatedTime(now);
                chunk.setUpdatedTime(now);
                if (knowledgeChunkMapper.insert(chunk) > 0) {
                    insertedChunks.add(chunk);
                }
            }
            if (insertedChunks.isEmpty()) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "No valid chunks were generated");
            }
            updateCounts(taskId, insertedChunks.size(), insertedChunks.size(),
                    before.getEsCount(), before.getMilvusCount(), false);

            updateStage(taskId, KnowledgeIndexTaskStage.EMBEDDING);
            List<float[]> vectors = embeddingService.embed(
                    insertedChunks.stream().map(this::indexedContent).toList());
            if (vectors == null || vectors.size() != insertedChunks.size()) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "Embedding result count does not match chunk count");
            }
            List<VectorRecordVO> records = buildVectorRecords(insertedChunks);
            for (int i = 0; i < records.size(); i++) {
                records.get(i).setVector(vectors.get(i));
            }

            updateStage(taskId, KnowledgeIndexTaskStage.VECTOR_WRITE);
            vectorStore.upsert(records);

            updateStage(taskId, KnowledgeIndexTaskStage.ES_WRITE);
            int esUpserted = upsertEsChunksIfAvailable(insertedChunks);
            refreshEsIndexIfAvailable();

            for (KnowledgeChunk chunk : insertedChunks) {
                chunk.setEmbeddingModel(resolveEmbeddingModel(doc));
                chunk.setVectorRefId(chunk.getDocId() + "-" + chunk.getId());
                chunk.setUpdatedTime(LocalDateTime.now());
                knowledgeChunkMapper.updateById(chunk);
            }

            updateStage(taskId, KnowledgeIndexTaskStage.VERIFY);
            verifyStagedChunks(docId, chunkIds(insertedChunks));
            activateVersion(doc, indexVersion, insertedChunks);
            activated = true;
            meterRegistry.counter("rag_ingest_active_version_switch_total", "status", "success")
                    .increment();

            updateStage(taskId, KnowledgeIndexTaskStage.CLEANUP);
            cleanupRetiredChunks(oldChunks);
            return new IndexResult(insertedChunks.size(), esUpserted, indexVersion, quality);
        } catch (Exception ex) {
            if (!activated) {
                meterRegistry.counter("rag_ingest_active_version_switch_total", "status", "not_switched")
                        .increment();
                cleanupChunksBestEffort(insertedChunks);
            }
            throw ex;
        }
    }

    private List<KnowledgeChunk> selectChunksByDoc(Long docId) {
        QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
        wrapper.eq("doc_id", docId);
        wrapper.orderByAsc("id");
        return knowledgeChunkMapper.selectList(wrapper);
    }

    private List<Long> chunkIds(List<KnowledgeChunk> chunks) {
        if (chunks == null) {
            return List.of();
        }
        return chunks.stream()
                .map(KnowledgeChunk::getId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private void verifyStagedChunks(Long docId, List<Long> stagedIds) {
        if (stagedIds.isEmpty()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Staging version contains no chunks");
        }
        for (int attempt = 1; attempt <= VERIFY_MAX_ATTEMPTS; attempt++) {
            refreshEsIndexIfAvailable();
            StorageConsistencyDocVO snapshot = consistencyService.inspectDoc(
                    docId, Math.max(DEFAULT_CONSISTENCY_LIMIT, stagedIds.size() + 100));
            if (snapshot.getDbChunkIds().containsAll(stagedIds)
                    && snapshot.getEsChunkIds().containsAll(stagedIds)
                    && snapshot.getMilvusChunkIds().containsAll(stagedIds)) {
                return;
            }
            sleepQuietly(VERIFY_RETRY_INTERVAL_MS);
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR,
                "Staging version failed DB/ES/Milvus presence verification");
    }

    private void activateVersion(KnowledgeDoc doc,
                                 String indexVersion,
                                 List<KnowledgeChunk> insertedChunks) {
        List<Long> retiredDocIds = knowledgeDocService.activateIndexedVersion(doc.getId(), indexVersion);
        for (KnowledgeChunk chunk : insertedChunks) {
            chunk.setIndexStatus("ACTIVE");
            chunk.setUpdatedTime(LocalDateTime.now());
            knowledgeChunkMapper.updateById(chunk);
        }
        log.info("[KnowledgeIndexJob] active index version switched, docId={}, version={}, chunks={}, retiredDocIds={}",
                doc.getId(), indexVersion, insertedChunks.size(), retiredDocIds);
    }

    private void cleanupRetiredChunks(List<KnowledgeChunk> oldChunks) {
        List<Long> oldIds = chunkIds(oldChunks);
        if (oldIds.isEmpty()) {
            return;
        }
        vectorStore.deleteByChunkIds(oldIds);
        deleteEsByChunkIds(oldIds);
        knowledgeChunkMapper.deleteBatchIds(oldIds);
    }

    private void cleanupVersionBestEffort(Long docId, String indexVersion) {
        QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
        wrapper.eq("doc_id", docId);
        wrapper.eq("index_version", indexVersion);
        cleanupChunksBestEffort(knowledgeChunkMapper.selectList(wrapper));
    }

    private void cleanupChunksBestEffort(List<KnowledgeChunk> chunks) {
        List<Long> ids = chunkIds(chunks);
        if (ids.isEmpty()) {
            return;
        }
        try {
            vectorStore.deleteByChunkIds(ids);
        } catch (Exception ex) {
            log.warn("[KnowledgeIndexJob] staged Milvus cleanup failed, chunkIds={}, reason={}",
                    ids, ex.getMessage());
        }
        try {
            deleteEsByChunkIds(ids);
        } catch (Exception ex) {
            log.warn("[KnowledgeIndexJob] staged ES cleanup failed, chunkIds={}, reason={}",
                    ids, ex.getMessage());
        }
        knowledgeChunkMapper.deleteBatchIds(ids);
    }

    private KnowledgeMetadata buildMetadata(KnowledgeDoc doc, ChunkPayloadVO payload) {
        KnowledgeMetadata metadata = KnowledgeMetadata.of(
                doc.getId(),
                payload.getChunkIndex(),
                doc.getSourceType(),
                resolveCategory(doc),
                resolveRiskLevel(doc),
                doc.getTitle(),
                doc.getSourceUri(),
                parseTags(doc.getTags())
        );
        metadata.setSourceTrustLevel(doc.getSourceTrustLevel());
        metadata.setDocVersion(doc.getDocVersion());
        metadata.setEffectiveFrom(doc.getEffectiveFrom() == null ? "" : doc.getEffectiveFrom().toString());
        metadata.setEffectiveTo(doc.getEffectiveTo() == null ? "" : doc.getEffectiveTo().toString());
        metadata.setDocStatus(KnowledgeDocStatus.ACTIVE.name());
        metadata.setDisabledReason(doc.getDisabledReason());
        metadata.setLogicalKnowledgeId(doc.getLogicalKnowledgeId());
        metadata.setHeadingPath(payload.getHeadingPath());
        metadata.setPageFrom(payload.getPageFrom());
        metadata.setPageTo(payload.getPageTo());
        metadata.setContentType(payload.getContentType());
        metadata.setSourceStart(payload.getSourceStart());
        metadata.setSourceEnd(payload.getSourceEnd());
        metadata.setSemanticTopic(payload.getSemanticTopic());
        metadata.setChunkStrategyVersion(StringUtils.hasText(doc.getChunkStrategyVersion())
                ? doc.getChunkStrategyVersion() : "legacy-v1");
        metadata.setOverlap(payload.getOverlap());
        metadata.setOverlapChars(payload.getOverlapChars());
        metadata.normalize();
        return metadata;
    }

    private List<VectorRecordVO> buildVectorRecords(List<KnowledgeChunk> insertedChunks) {
        List<VectorRecordVO> records = new ArrayList<>();
        for (KnowledgeChunk chunk : insertedChunks) {
            VectorRecordVO record = new VectorRecordVO();
            record.setDocId(chunk.getDocId());
            record.setChunkId(chunk.getId());
            record.setContent(indexedContent(chunk));
            record.setMetadata(chunk.getMetadata());
            records.add(record);
        }
        return records;
    }

    private void finishSuccess(Long taskId,
                               StorageConsistencyDocVO consistency,
                               int chunkCount,
                               Map<String, Object> detail) {
        LocalDateTime finishNow = LocalDateTime.now();
        KnowledgeIndexTask task = requireTask(taskId);
        updateTask(taskId, patch -> {
            patch.setStatus(KnowledgeIndexTaskStatus.SUCCESS);
            patch.setStage(KnowledgeIndexTaskStage.FINISHED);
            patch.setChunkCount(chunkCount);
            patch.setDbCount(consistency.getDbCount());
            patch.setEsCount(consistency.getEsCount());
            patch.setMilvusCount(consistency.getMilvusCount());
            patch.setConsistent(Boolean.TRUE.equals(consistency.getConsistent()));
            patch.setErrorMsg(null);
            patch.setDetailJson(toJson(detail));
            patch.setFinishedAt(finishNow);
            patch.setElapsedMs(elapsedMs(task.getStartedAt(), finishNow));
            patch.setUpdatedTime(finishNow);
        });
    }

    private void failTask(Long taskId, String message, Map<String, Object> detail) {
        LocalDateTime failNow = LocalDateTime.now();
        KnowledgeIndexTask task = requireTask(taskId);
        StorageConsistencyDocVO consistency;
        try {
            consistency = consistencyService.inspectDoc(task.getDocId(), DEFAULT_CONSISTENCY_LIMIT);
        } catch (Exception consistencyError) {
            consistency = new StorageConsistencyDocVO();
            consistency.setDocId(task.getDocId());
            consistency.setDbCount(task.getDbCount() == null ? 0 : task.getDbCount());
            consistency.setEsCount(task.getEsCount() == null ? 0 : task.getEsCount());
            consistency.setMilvusCount(task.getMilvusCount() == null ? 0 : task.getMilvusCount());
            consistency.setConsistent(false);
            log.warn("[KnowledgeIndexJob] failure snapshot unavailable, taskId={}, reason={}",
                    taskId, consistencyError.getMessage());
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (detail != null) {
            merged.putAll(detail);
        }
        merged.put("consistency", consistency);
        StorageConsistencyDocVO failureSnapshot = consistency;
        updateTask(taskId, patch -> {
            patch.setStatus(KnowledgeIndexTaskStatus.FAILED);
            patch.setStage(KnowledgeIndexTaskStage.FINISHED);
            patch.setDbCount(failureSnapshot.getDbCount());
            patch.setEsCount(failureSnapshot.getEsCount());
            patch.setMilvusCount(failureSnapshot.getMilvusCount());
            patch.setConsistent(Boolean.TRUE.equals(failureSnapshot.getConsistent()));
            patch.setErrorMsg(message);
            patch.setDetailJson(toJson(merged));
            patch.setFinishedAt(failNow);
            patch.setElapsedMs(elapsedMs(task.getStartedAt(), failNow));
            patch.setUpdatedTime(failNow);
        });
    }

    private void updateStage(Long taskId, KnowledgeIndexTaskStage stage) {
        updateTask(taskId, patch -> {
            patch.setStage(stage);
            patch.setUpdatedTime(LocalDateTime.now());
        });
    }

    private void updateCounts(Long taskId,
                              int chunkCount,
                              int dbCount,
                              Integer esCount,
                              Integer milvusCount,
                              boolean consistent) {
        updateTask(taskId, patch -> {
            patch.setChunkCount(chunkCount);
            patch.setDbCount(dbCount);
            patch.setEsCount(esCount == null ? 0 : esCount);
            patch.setMilvusCount(milvusCount == null ? 0 : milvusCount);
            patch.setConsistent(consistent);
            patch.setUpdatedTime(LocalDateTime.now());
        });
    }

    private void updateTask(Long taskId, java.util.function.Consumer<KnowledgeIndexTask> mutator) {
        KnowledgeIndexTask task = requireTask(taskId);
        mutator.accept(task);
        knowledgeIndexTaskMapper.updateById(task);
    }

    private KnowledgeDoc validateDoc(Long docId, KnowledgeIndexTaskType taskType) {
        if (docId == null || docId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识文档ID不合法");
        }
        KnowledgeDoc doc = knowledgeDocService.getByIdOrThrow(docId);
        if (!StringUtils.hasText(doc.getContent())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识文档内容为空，无法索引");
        }
        boolean retired = KnowledgeDocStatus.RETIRED.name().equalsIgnoreCase(doc.getStatus());
        if (KnowledgeIndexTaskType.ROLLBACK.equals(taskType) && !retired) {
            throw new BusinessException(ResultCode.CONFLICT, "回滚目标必须是 RETIRED 状态的知识版本");
        }
        if (KnowledgeIndexTaskType.ROLLBACK.equals(taskType)
                && "RUNNING".equalsIgnoreCase(doc.getCleanupStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "目标版本正在清理索引，请稍后重试回滚");
        }
        if (retired && !KnowledgeIndexTaskType.ROLLBACK.equals(taskType)) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "RETIRED 版本只能通过管理员受控回滚重新激活");
        }
        return doc;
    }

    private KnowledgeIndexTask requireTask(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务ID不合法");
        }
        KnowledgeIndexTask task = knowledgeIndexTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识入库任务不存在");
        }
        return task;
    }

    private KnowledgeReindexTriggerVO triggerVO(KnowledgeIndexTask task, String message) {
        KnowledgeReindexTriggerVO vo = new KnowledgeReindexTriggerVO();
        vo.setTaskId(task.getId());
        vo.setDocId(task.getDocId());
        vo.setStatus(task.getStatus());
        vo.setMessage(message);
        vo.setTask(toVO(task));
        return vo;
    }

    private KnowledgeIndexTaskVO toVO(KnowledgeIndexTask task) {
        KnowledgeIndexTaskVO vo = new KnowledgeIndexTaskVO();
        BeanUtils.copyProperties(task, vo);
        return vo;
    }

    private String resolveCategory(KnowledgeDoc doc) {
        List<String> tags = parseTags(doc.getTags());
        if (!tags.isEmpty()) {
            return tags.get(0);
        }
        if (StringUtils.hasText(doc.getSourceType())) {
            return doc.getSourceType().trim();
        }
        return KnowledgeMetadata.UNKNOWN;
    }

    private String resolveRiskLevel(KnowledgeDoc doc) {
        List<String> tags = parseTags(doc.getTags());
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) {
                continue;
            }
            String normalized = tag.trim().toUpperCase();
            if ("HIGH".equals(normalized) || "MEDIUM".equals(normalized) || "LOW".equals(normalized)) {
                return normalized;
            }
            if ("高".equals(tag) || tag.contains("高风险")) {
                return "HIGH";
            }
            if ("中".equals(tag) || tag.contains("中风险") || tag.contains("中等风险")) {
                return "MEDIUM";
            }
            if ("低".equals(tag) || tag.contains("低风险")) {
                return "LOW";
            }
        }
        return KnowledgeMetadata.UNKNOWN;
    }

    private List<String> parseTags(String tags) {
        List<String> list = new ArrayList<>();
        if (!StringUtils.hasText(tags)) {
            return Collections.emptyList();
        }
        String[] split = tags.split("[,，;；\\s]+");
        for (String string : split) {
            if (StringUtils.hasText(string)) {
                list.add(string.trim());
            }
        }
        return list;
    }

    private void deleteEsByChunkIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        ElasticsearchOperations esOps = elasticsearchOperationsProvider.getIfAvailable();
        if (esOps == null) {
            throw new IllegalStateException("ElasticsearchOperations is unavailable");
        }
        ensureEsIndex(esOps);
        for (Long chunkId : chunkIds) {
            if (chunkId != null) {
                esOps.delete(String.valueOf(chunkId), IndexCoordinates.of(ES_INDEX_NAME));
            }
        }
        refreshEsIndexIfAvailable();
    }

    private int upsertEsChunksIfAvailable(List<KnowledgeChunk> insertedChunks) {
        if (insertedChunks == null || insertedChunks.isEmpty()) {
            return 0;
        }
        ElasticsearchOperations esOps = elasticsearchOperationsProvider.getIfAvailable();
        if (esOps == null) {
            return 0;
        }
        ensureEsIndex(esOps);
        int success = 0;
        for (KnowledgeChunk chunk : insertedChunks) {
            if (chunk.getId() == null) {
                continue;
            }
            try {
                KnowledgeMetadata metadata = chunk.getMetadata();
                KnowledgeChunkEsDoc doc = new KnowledgeChunkEsDoc();
                doc.setId(String.valueOf(chunk.getId()));
                doc.setChunkId(chunk.getId());
                doc.setDocId(chunk.getDocId());
                doc.setContent(indexedContent(chunk));
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
                doc.setIndexVersion(metadata == null ? "" : safe(metadata.getIndexVersion()));
                doc.setLogicalKnowledgeId(metadata == null ? "" : safe(metadata.getLogicalKnowledgeId()));

                esOps.save(doc, IndexCoordinates.of(ES_INDEX_NAME));
                success++;
            } catch (Exception e) {
                log.warn("[KnowledgeIndexJob] ES upsert failed, chunkId={}, docId={}, reason={}",
                        chunk.getId(), chunk.getDocId(), e.getMessage());
            }
        }
        log.info("[KnowledgeIndexJob] ES upsert finished, total={}, success={}", insertedChunks.size(), success);
        return success;
    }

    private VerifySnapshot waitForConsistency(Long docId, int limit) {
        StorageConsistencyDocVO latest = null;
        for (int attempt = 1; attempt <= VERIFY_MAX_ATTEMPTS; attempt++) {
            refreshEsIndexIfAvailable();
            latest = consistencyService.inspectDoc(docId, limit);
            if (Boolean.TRUE.equals(latest.getConsistent())) {
                return new VerifySnapshot(latest, attempt);
            }
            sleepQuietly(VERIFY_RETRY_INTERVAL_MS);
        }
        return new VerifySnapshot(latest, VERIFY_MAX_ATTEMPTS);
    }

    private void refreshEsIndexIfAvailable() {
        ElasticsearchOperations esOps = elasticsearchOperationsProvider.getIfAvailable();
        if (esOps == null) {
            return;
        }
        try {
            esOps.indexOps(KnowledgeChunkEsDoc.class).refresh();
        } catch (Exception e) {
            log.warn("[KnowledgeIndexJob] ES refresh failed, reason={}", e.getMessage());
        }
    }

    private void ensureEsIndex(ElasticsearchOperations esOps) {
        try {
            IndexOperations indexOps = esOps.indexOps(KnowledgeChunkEsDoc.class);
            if (!indexOps.exists()) {
                boolean created = indexOps.create();
                log.info("[KnowledgeIndexJob] ES index created before operation, index={}, created={}", ES_INDEX_NAME, created);
            }
            indexOps.putMapping(indexOps.createMapping(KnowledgeChunkEsDoc.class));
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "ES索引初始化失败: " + ex.getMessage());
        }
    }

    private String safe(String text) {
        return StringUtils.hasText(text) ? text : "";
    }

    private String indexedContent(KnowledgeChunk chunk) {
        KnowledgeMetadata metadata = chunk.getMetadata();
        if (metadata == null || metadata.getHeadingPath() == null || metadata.getHeadingPath().isEmpty()) {
            return chunk.getContent();
        }
        return String.join(" > ", metadata.getHeadingPath()) + "\n" + chunk.getContent();
    }

    private String resolveEmbeddingModel(KnowledgeDoc doc) {
        return StringUtils.hasText(doc.getEmbeddingModel())
                ? doc.getEmbeddingModel()
                : EMBEDDING_MODEL_NAME;
    }

    private String taskMessage(KnowledgeIndexTask task,
                               String submitted,
                               String success,
                               String failed) {
        if (KnowledgeIndexTaskStatus.SUCCESS.equals(task.getStatus())) {
            return success;
        }
        if (KnowledgeIndexTaskStatus.FAILED.equals(task.getStatus())
                || KnowledgeIndexTaskStatus.DEAD_LETTERED.equals(task.getStatus())) {
            return failed + ": " + safe(task.getErrorMsg());
        }
        return submitted;
    }

    private String safeMsg(Exception e) {
        String raw = e.getMessage() == null ? "未知异常" : e.getMessage();
        return raw.substring(0, Math.min(500, raw.length()));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Long elapsedMs(LocalDateTime start, LocalDateTime finish) {
        if (start == null || finish == null) {
            return null;
        }
        return Duration.between(start, finish).toMillis();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record IndexResult(int insertedChunks,
                               int esUpserted,
                               String indexVersion,
                               ChunkQualityReportVO quality) {
    }

    private record VerifySnapshot(StorageConsistencyDocVO consistency, int attempts) {
    }

}
