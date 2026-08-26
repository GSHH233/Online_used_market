package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocCreateRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocGovernanceUpdateDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeRepairBatchRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskType;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeDocService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeDocumentUploadService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeIndexService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeChunkMapper;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeChunk;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeDocVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeDocumentUploadVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeIndexTaskVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeRepairBatchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeReindexTriggerVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;

import java.util.List;
import java.util.Map;

/**
 * @author Admin
 * @ClassName: KnowledgeDocController
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-24  0:12
 * @Version:
 */
@RestController
@RequestMapping("/api/ai/knowledge")
@RequiredArgsConstructor
public class KnowledgeDocController {
    private final KnowledgeDocService knowledgeDocService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final VectorStore vectorStore;
    private final KnowledgeDocumentUploadService knowledgeDocumentUploadService;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    @PostMapping(value = "/docs/upload", consumes = "multipart/form-data")
    public Result<KnowledgeDocumentUploadVO> uploadDocument(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(defaultValue = "GUIDE") KnowledgeSourceType sourceType,
            @RequestParam(required = false) String tags,
            @RequestParam(defaultValue = "MEDIUM") String sourceTrustLevel,
            @RequestParam(defaultValue = "v1") String docVersion,
            @RequestParam(required = false) String logicalKnowledgeId,
            @RequestParam(defaultValue = "true") boolean async) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_upload");
        return Result.success(knowledgeDocumentUploadService.upload(
                file, title, sourceType, tags, sourceTrustLevel, docVersion, logicalKnowledgeId, async));
    }

    @PostMapping("/docs")
    public Result<KnowledgeDocVO> createDoc(@Valid @RequestBody KnowledgeDocCreateRequestDTO dto) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_create");
        return Result.success(knowledgeDocService.createDoc(dto));
    }

    @GetMapping("/docs")
    public Result<List<KnowledgeDocVO>> listDocs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus status,
            @RequestParam(required = false) KnowledgeSourceType sourceType,
            @RequestParam(defaultValue = "30") Integer limit) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_list");
        return Result.success(knowledgeDocService.list(keyword, status, sourceType, limit));
    }

    @GetMapping("/docs/{docId}")
    public Result<KnowledgeDocVO> getDoc(@PathVariable Long docId) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_detail");
        return Result.success(knowledgeDocService.getVO(docId));
    }

    @PatchMapping("/docs/{docId}/governance")
    public Result<KnowledgeDocVO> updateGovernance(@PathVariable Long docId,
                                                   @RequestBody KnowledgeDocGovernanceUpdateDTO dto) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_governance");
        return Result.success(knowledgeDocService.updateGovernance(docId, dto));
    }

    @PostMapping("/reindex/{docId}")
    public Result<KnowledgeReindexTriggerVO> reindex(@PathVariable Long docId) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_reindex_legacy");
        throw legacyWriteOperation("REPAIR_KNOWLEDGE_DOC", docId);
    }

    @PostMapping("/jobs/ingest/{docId}")
    public Result<KnowledgeReindexTriggerVO> ingest(@PathVariable Long docId,
                                                    @RequestParam(defaultValue = "false") Boolean async) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_ingest");
        return Result.success(knowledgeIndexService.triggerIngest(docId, async));
    }

    @PostMapping("/repair/{docId}")
    public Result<KnowledgeReindexTriggerVO> repair(@PathVariable Long docId,
                                                    @RequestParam(defaultValue = "false") Boolean forceReindex,
                                                    @RequestParam(defaultValue = "false") Boolean async) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_repair_legacy");
        throw legacyWriteOperation("REPAIR_KNOWLEDGE_DOC", docId);
    }

    @PostMapping("/rollback/{docId}")
    public Result<KnowledgeReindexTriggerVO> rollback(@PathVariable Long docId) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_rollback_legacy");
        throw new BusinessException(ResultCode.CONFLICT,
                "知识版本回滚已收口为受控操作计划，请调用 POST /api/ai/operations/plans，actionCode="
                        + "ROLLBACK_KNOWLEDGE_REVISION，params.targetDocId=" + docId);
    }

    @PostMapping("/repair/batch")
    public Result<KnowledgeRepairBatchResponseVO> repairBatch(@RequestBody(required = false) KnowledgeRepairBatchRequestDTO dto) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_repair_batch_legacy");
        throw new BusinessException(ResultCode.CONFLICT,
                "批量 Repair 已收口为受控操作计划，请调用 POST /api/ai/operations/plans，actionCode=REPAIR_KNOWLEDGE_BATCH");
    }

    @GetMapping("/jobs/{taskId}")
    public Result<KnowledgeIndexTaskVO> getTask(@PathVariable Long taskId) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_job_detail");
        return Result.success(knowledgeIndexService.getTask(taskId));
    }

    @GetMapping("/jobs")
    public Result<List<KnowledgeIndexTaskVO>> listTasks(@RequestParam(required = false) Long docId,
                                                        @RequestParam(required = false) KnowledgeIndexTaskStatus status,
                                                        @RequestParam(required = false) KnowledgeIndexTaskType taskType,
                                                        @RequestParam(defaultValue = "20") Integer limit) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_job_list");
        return Result.success(knowledgeIndexService.listTasks(docId, status, taskType, limit));
    }

    @PostMapping("/search")
    public Result<KnowledgeSearchResponseVO> search(@Valid @RequestBody KnowledgeSearchRequestDTO dto) {
        if ((dto.getMode() == null
                || org.example.online_used_marketplace.modules.ai.enums.RetrievalMode.HYBRID.equals(dto.getMode()))
                && Boolean.FALSE.equals(dto.getRewriteEnabled())) {
            return Result.success(knowledgeSearchService.searchHybrid(
                    dto.getQuestion(), dto.getTopK(), dto.getFilter(), false));
        }
        return Result.success(knowledgeSearchService.search(dto.getQuestion(), dto.getTopK(), dto.getFilter(), dto.getMode()));
    }

    @GetMapping("/debug/milvus/by-doc/{docId}")
    public Result<List<Map<String, Object>>> debugQueryByDocId(@PathVariable Long docId){
        ToolAccessGuard.requireInternalEndpoint("knowledge_milvus_debug");
        return Result.success(vectorStore.debugQueryByDocId(docId,200));
    }

    @GetMapping("/debug/chunks/by-doc/{docId}")
    public Result<List<KnowledgeChunk>> debugChunksByDocId(@PathVariable Long docId) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_chunk_debug");
        QueryWrapper<KnowledgeChunk> wrapper = new QueryWrapper<>();
        wrapper.eq("doc_id", docId);
        wrapper.orderByAsc("chunk_index", "id");
        return Result.success(knowledgeChunkMapper.selectList(wrapper));
    }

    private BusinessException legacyWriteOperation(String actionCode, Long docId) {
        return new BusinessException(ResultCode.CONFLICT,
                "知识库写操作已收口为受控操作计划，请调用 POST /api/ai/operations/plans，actionCode="
                        + actionCode + "，params.docId=" + docId);
    }
}
