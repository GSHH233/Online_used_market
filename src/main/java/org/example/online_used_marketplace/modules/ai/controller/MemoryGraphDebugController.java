package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.MemoryGraphExtractRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.MemoryGraphService;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphEdgeVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphExtractResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphNodeVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/memory-graph")
@RequiredArgsConstructor
public class MemoryGraphDebugController {

    private final MemoryGraphService memoryGraphService;

    @PostMapping("/debug/extract")
    public Result<MemoryGraphExtractResponseVO> extract(@Valid @RequestBody MemoryGraphExtractRequestDTO request) {
        return Result.success(memoryGraphService.extractAndSave(
                request.getUserId(),
                request.getSessionId(),
                request.getContent(),
                request.getSource()
        ));
    }

    @GetMapping("/debug/nodes")
    public Result<List<MemoryGraphNodeVO>> nodes(@RequestParam String userId,
                                                 @RequestParam(required = false) String topic,
                                                 @RequestParam(required = false) Integer limit) {
        return Result.success(memoryGraphService.listNodes(userId, topic, limit));
    }

    @GetMapping("/debug/edges")
    public Result<List<MemoryGraphEdgeVO>> edges(@RequestParam String userId,
                                                 @RequestParam(required = false) String fromLabel,
                                                 @RequestParam(required = false) String toLabel,
                                                 @RequestParam(required = false) Integer limit) {
        return Result.success(memoryGraphService.listEdges(userId, fromLabel, toLabel, limit));
    }
}
