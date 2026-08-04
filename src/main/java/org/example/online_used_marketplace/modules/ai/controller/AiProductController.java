package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.ProductOptimizeRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.ProductRiskOutcomeRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.PublishAssistFeedbackRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.PublishAssistRecordQueryDTO;
import org.example.online_used_marketplace.modules.ai.service.AiProductService;
import org.example.online_used_marketplace.modules.ai.service.AiPublishAssistService;
import org.example.online_used_marketplace.modules.ai.service.PublishAssistFeedbackService;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductRiskOutcomeVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistMetricsVO;
import org.example.online_used_marketplace.modules.ai.vo.PublishAssistRecordVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author Admin
 * @ClassName: AiProductController
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-21  0:20
 * @Version:
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai")
public class AiProductController {
    private final AiProductService aiProductService;
    private final AiPublishAssistService aiPublishAssistService;
    private final PublishAssistFeedbackService publishAssistFeedbackService;
    @PostMapping("/products/optimize")
    public Result<ProductOptimizeResponseVO> optimizeProductInfo(@Valid @RequestBody ProductOptimizeRequestDTO dto) {
        return Result.success(aiProductService.optimizeProductInfo(dto));
    }
    @PostMapping("/products/publish-assist")
    public Result<ProductPublishAssistVO> publishAssist(@Valid @RequestBody ProductPublishAssistRequestDTO dto){
        return Result.success(aiPublishAssistService.assist(dto, ToolAccessGuard.requireAuthenticatedUserId()));
    }

    @PostMapping("/products/publish-assist/{assistId}/feedback")
    public Result<PublishAssistRecordVO> feedback(@PathVariable String assistId,
                                                   @Valid @RequestBody PublishAssistFeedbackRequestDTO dto) {
        return Result.success(publishAssistFeedbackService.feedback(
                assistId, ToolAccessGuard.requireAuthenticatedUserId(), dto));
    }

    @GetMapping("/products/publish-assist/admin/records")
    public Result<List<PublishAssistRecordVO>> listAssistRecords(@Valid @ModelAttribute PublishAssistRecordQueryDTO dto) {
        ToolAccessGuard.requireInternalEndpoint("publish_assist_record_list");
        return Result.success(publishAssistFeedbackService.listRecords(dto));
    }

    @GetMapping("/products/publish-assist/admin/records/{assistId}")
    public Result<PublishAssistRecordVO> getInternalAssist(@PathVariable String assistId) {
        ToolAccessGuard.requireInternalEndpoint("publish_assist_record_detail");
        return Result.success(publishAssistFeedbackService.getInternal(assistId));
    }

    @GetMapping("/products/publish-assist/{assistId}")
    public Result<PublishAssistRecordVO> getAssist(@PathVariable String assistId) {
        return Result.success(publishAssistFeedbackService.getOwned(
                assistId, ToolAccessGuard.requireAuthenticatedUserId()));
    }

    @PostMapping("/products/{productId}/risk-outcomes")
    public Result<ProductRiskOutcomeVO> recordOutcome(@PathVariable Long productId,
                                                       @Valid @RequestBody ProductRiskOutcomeRequestDTO dto) {
        Long operatorId = Boolean.TRUE.equals(dto.getIsFinal())
                ? ToolAccessGuard.requireAdminUserId("publish_risk_final_outcome")
                : ToolAccessGuard.requireInternalUserId("publish_risk_auto_outcome");
        return Result.success(publishAssistFeedbackService.recordOutcome(productId, operatorId, dto));
    }

    @GetMapping("/products/publish-assist/metrics")
    public Result<PublishAssistMetricsVO> metrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        ToolAccessGuard.requireInternalEndpoint("publish_assist_metrics");
        return Result.success(publishAssistFeedbackService.metrics(from, to));
    }

}
