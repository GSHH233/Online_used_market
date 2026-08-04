package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeGapLinkDocDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeGapUpdateDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeGapUnresolvedFeedbackDTO;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeGapService;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeGapMetricsVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeGapRegressionVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeGapVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/knowledge/gaps")
public class KnowledgeGapController {

    private final KnowledgeGapService knowledgeGapService;

    @PostMapping("/feedback/unresolved")
    public Result<KnowledgeGapVO> unresolved(@Valid @RequestBody KnowledgeGapUnresolvedFeedbackDTO request) {
        ToolAccessGuard.requireAuthenticatedUserId();
        return Result.success(knowledgeGapService.captureUserUnresolved(request));
    }

    @GetMapping
    public Result<List<KnowledgeGapVO>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String priority,
                                             @RequestParam(defaultValue = "20") Integer limit) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_gap_list");
        return Result.success(knowledgeGapService.list(status, priority, limit));
    }

    @GetMapping("/{gapId}")
    public Result<KnowledgeGapVO> get(@PathVariable Long gapId) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_gap_detail");
        return Result.success(knowledgeGapService.get(gapId));
    }

    @PatchMapping("/{gapId}")
    public Result<KnowledgeGapVO> update(@PathVariable Long gapId,
                                         @RequestBody KnowledgeGapUpdateDTO request) {
        if (request != null && "DISMISSED".equalsIgnoreCase(request.getStatus())) {
            ToolAccessGuard.requireAdminEndpoint("knowledge_gap_dismiss");
        } else {
            ToolAccessGuard.requireInternalEndpoint("knowledge_gap_update");
        }
        return Result.success(knowledgeGapService.update(gapId, request));
    }

    @PostMapping("/{gapId}/link-doc")
    public Result<KnowledgeGapVO> linkDoc(@PathVariable Long gapId,
                                          @Valid @RequestBody KnowledgeGapLinkDocDTO request) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_gap_link_doc");
        return Result.success(knowledgeGapService.linkDoc(gapId, request));
    }

    @PostMapping("/{gapId}/regress")
    public Result<KnowledgeGapRegressionVO> regress(@PathVariable Long gapId) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_gap_regress");
        return Result.success(knowledgeGapService.regress(gapId));
    }

    @GetMapping("/{gapId}/regressions")
    public Result<List<KnowledgeGapRegressionVO>> regressions(@PathVariable Long gapId,
                                                               @RequestParam(defaultValue = "20") Integer limit) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_gap_regressions");
        return Result.success(knowledgeGapService.listRegressions(gapId, limit));
    }

    @GetMapping("/metrics")
    public Result<KnowledgeGapMetricsVO> metrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        ToolAccessGuard.requireInternalEndpoint("knowledge_gap_metrics");
        return Result.success(knowledgeGapService.metrics(from, to));
    }
}
