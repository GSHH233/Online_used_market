package org.example.online_used_marketplace.modules.ai.controller;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.AiopsRunbookExecuteRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.AiopsRunbookService;
import org.example.online_used_marketplace.modules.ai.vo.AiopsRunbookActionVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/aiops/runbooks")
public class AiopsRunbookController {

    private final AiopsRunbookService aiopsRunbookService;

    @GetMapping
    public Result<List<AiopsRunbookActionVO>> listRunbooks() {
        ToolAccessGuard.requireInternalEndpoint("aiops_runbook_list");
        return Result.success(aiopsRunbookService.listRunbooks());
    }

    @PostMapping("/execute")
    public Result<AiopsRunbookActionVO> execute(@RequestBody AiopsRunbookExecuteRequestDTO request) {
        if (request != null && Boolean.FALSE.equals(request.getDryRun())) {
            ToolAccessGuard.requireAdminEndpoint("aiops_runbook_execute");
        } else {
            ToolAccessGuard.requireInternalEndpoint("aiops_runbook_dry_run");
        }
        return Result.success(aiopsRunbookService.execute(request));
    }
}
