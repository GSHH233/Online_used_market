package org.example.online_used_marketplace.modules.ai.controller;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeStorageConsistencyService;
import org.example.online_used_marketplace.modules.ai.vo.StorageConsistencyReportVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/storage/debug")
public class StorageConsistencyDebugController {

    private final KnowledgeStorageConsistencyService consistencyService;

    @GetMapping("/consistency")
    public Result<StorageConsistencyReportVO> consistency(@RequestParam(required = false) Long docId,
                                                         @RequestParam(defaultValue = "10") Integer sampleDocs,
                                                         @RequestParam(defaultValue = "200") Integer perDocLimit) {
        ToolAccessGuard.requireInternalEndpoint("storage_consistency_debug");
        return Result.success(consistencyService.inspect(docId, sampleDocs, perDocLimit));
    }
}
