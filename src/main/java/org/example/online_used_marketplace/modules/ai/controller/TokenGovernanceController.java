package org.example.online_used_marketplace.modules.ai.controller;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.TokenGovernancePreviewRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.TokenGovernanceService;
import org.example.online_used_marketplace.modules.ai.service.TokenUsageService;
import org.example.online_used_marketplace.modules.ai.token.TokenBudgetResult;
import org.example.online_used_marketplace.modules.ai.token.TokenPromptSection;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.example.online_used_marketplace.modules.ai.token.TokenUsageRecord;
import org.example.online_used_marketplace.modules.ai.vo.TokenUsageMetricsVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/token-governance")
public class TokenGovernanceController {

    private final TokenGovernanceService tokenGovernanceService;
    private final TokenUsageService tokenUsageService;

    @PostMapping("/preview")
    public Result<TokenBudgetResult> preview(@RequestBody TokenGovernancePreviewRequestDTO request) {
        ToolAccessGuard.requireInternalEndpoint("token_governance_preview");
        TokenScene scene = TokenScene.from(request == null ? null : request.getScene());
        List<TokenPromptSection> sections = request == null || request.getSections() == null
                ? List.of()
                : request.getSections().stream()
                .map(section -> TokenPromptSection.of(
                        section.getName(),
                        section.getContent(),
                        section.getPriority() == null ? 50 : section.getPriority(),
                        Boolean.TRUE.equals(section.getProtectedSection())))
                .toList();
        return Result.success(tokenGovernanceService.assemble(scene, sections));
    }

    @GetMapping("/usages")
    public Result<List<TokenUsageRecord>> usages(@RequestParam(required = false) String traceId,
                                                 @RequestParam(defaultValue = "50") Integer limit) {
        ToolAccessGuard.requireInternalEndpoint("token_governance_usages");
        return Result.success(tokenUsageService.recent(traceId, limit));
    }

    @GetMapping("/metrics")
    public Result<TokenUsageMetricsVO> metrics(@RequestParam(defaultValue = "100") Integer recentLimit) {
        ToolAccessGuard.requireInternalEndpoint("token_governance_metrics");
        return Result.success(tokenUsageService.metrics(recentLimit));
    }
}
