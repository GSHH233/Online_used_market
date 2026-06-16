package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocCreateRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocGovernanceUpdateDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeRepairBatchRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeSearchRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskType;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeDocService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeIndexService;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeSearchService;
import org.example.online_used_marketplace.modules.ai.service.VectorStore;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeDocVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeIndexTaskVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeRepairBatchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeReindexTriggerVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeSearchResponseVO;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/docs")
    public Result<KnowledgeDocVO> createDoc(@Valid @RequestBody KnowledgeDocCreateRequestDTO dto) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_create");
        return Result.success(knowledgeDocService.createDoc(dto));
    }

    @PatchMapping("/docs/{docId}/governance")
    public Result<KnowledgeDocVO> updateGovernance(@PathVariable Long docId,
                                                   @RequestBody KnowledgeDocGovernanceUpdateDTO dto) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_governance");
        return Result.success(knowledgeDocService.updateGovernance(docId, dto));
    }

    @PostMapping("/reindex/{docId}")
    public Result<KnowledgeReindexTriggerVO> reindex(@PathVariable Long docId) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_reindex");
        return Result.success(knowledgeIndexService.triggerReindex(docId));
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
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_repair");
        return Result.success(knowledgeIndexService.repairDoc(docId, forceReindex, async));
    }

    @PostMapping("/repair/batch")
    public Result<KnowledgeRepairBatchResponseVO> repairBatch(@RequestBody(required = false) KnowledgeRepairBatchRequestDTO dto) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_doc_repair_batch");
        return Result.success(knowledgeIndexService.repairBatch(dto));
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
        return Result.success(knowledgeSearchService.search(dto.getQuestion(), dto.getTopK(), dto.getFilter(), dto.getMode()));
    }

    @GetMapping("/debug/milvus/by-doc/{docId}")
    public Result<List<Map<String, Object>>> debugQueryByDocId(@PathVariable Long docId){
        ToolAccessGuard.requireInternalEndpoint("knowledge_milvus_debug");
        return Result.success(vectorStore.debugQueryByDocId(docId,200));
    }
}
