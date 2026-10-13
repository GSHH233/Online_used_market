package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.MemoryGraphExtractRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.MemoryGraphService;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphEdgeVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphExtractResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphNodeVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphCleanupVO;

@RestController
@RequestMapping("/api/ai/memory-graph")
@RequiredArgsConstructor
public class MemoryGraphDebugController {

    private final MemoryGraphService memoryGraphService;

    @Value("${ai.memory.debug.enabled:false}")
    private boolean debugEnabled;

    @PostMapping("/debug/extract")
    public Result<MemoryGraphExtractResponseVO> extract(@Valid @RequestBody MemoryGraphExtractRequestDTO request) {
        ToolAccessGuard.requireAdminEndpoint("memory_graph_debug_extract");
        requireDebugAccess();
        String owner = ownUserId(request.getUserId());
        return Result.success(memoryGraphService.extractAndSave(
                owner,
                request.getSessionId(),
                request.getContent(),
                request.getSource()
        ));
    }

    @GetMapping("/debug/nodes")
    public Result<List<MemoryGraphNodeVO>> nodes(@RequestParam String userId,
                                                 @RequestParam(required = false) String topic,
                                                 @RequestParam(required = false) Integer limit) {
        ToolAccessGuard.requireInternalEndpoint("memory_graph_debug_nodes");
        requireDebugAccess();
        return Result.success(memoryGraphService.listNodes(ownUserId(userId), topic, limit));
    }

    @GetMapping("/debug/edges")
    public Result<List<MemoryGraphEdgeVO>> edges(@RequestParam String userId,
                                                 @RequestParam(required = false) String fromLabel,
                                                 @RequestParam(required = false) String toLabel,
                                                 @RequestParam(required = false) Integer limit) {
        ToolAccessGuard.requireInternalEndpoint("memory_graph_debug_edges");
        requireDebugAccess();
        return Result.success(memoryGraphService.listEdges(ownUserId(userId), fromLabel, toLabel, limit));
    }

    @DeleteMapping("/debug/user")
    public Result<MemoryGraphCleanupVO> cleanupOwnGraph() {
        ToolAccessGuard.requireAdminEndpoint("memory_graph_debug_cleanup");
        requireDebugAccess();
        return Result.success(memoryGraphService.deleteUserGraph(ownUserId(null)));
    }

    private void requireDebugAccess() {
        if (!debugEnabled) {
            throw new BusinessException(ResultCode.FORBIDDEN, "记忆图调试接口未启用");
        }
    }

    private String ownUserId(String requestedUserId) {
        String owner = ToolAccessGuard.requireAuthenticatedUserId().toString();
        if (requestedUserId != null && !owner.equals(requestedUserId.trim())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能访问其他用户的记忆图");
        }
        return owner;
    }
}
