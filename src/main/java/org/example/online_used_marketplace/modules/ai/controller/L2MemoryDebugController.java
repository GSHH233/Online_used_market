package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.L2MemorySlotDeleteRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.L2MemorySlotWriteRequestDTO;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryDeleteCommand;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryMutationResult;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallQuery;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallResult;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryWriteCommand;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.service.UserProfileService;
import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;
import org.example.online_used_marketplace.modules.ai.vo.UserProfileVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai/l2-memory")
@RequiredArgsConstructor
public class L2MemoryDebugController {

    private final L2MemoryService l2MemoryService;
    private final UserProfileService userProfileService;

    @Value("${ai.memory.debug.enabled:false}")
    private boolean debugEnabled;

    @PostMapping("/debug/write")
    public Result<String> write(@RequestParam(required = false) String userId,
                                @RequestParam(required = false) String sessionId,
                                @RequestParam(required = false) String memoryType,
                                @RequestParam String content,
                                @RequestParam(required = false) Double importance,
                                @RequestParam(required = false) String metadataJson) {
        requireDebugAccess();
        ToolAccessGuard.requireAdminEndpoint("memory_debug_write");
        String uid = ownUserId(userId);
        String sid = StringUtils.hasText(sessionId) ? sessionId : "debug-session";
        String type = StringUtils.hasText(memoryType) ? memoryType : "FACT";
        double imp = importance == null ? 0.6D : importance;

        l2MemoryService.saveMemory(uid, sid, type, content, imp, metadataJson);
        return Result.success("ok");
    }

    @PostMapping("/debug/slot")
    public Result<L2MemoryMutationResult> upsertSlot(@Valid @RequestBody L2MemorySlotWriteRequestDTO request) {
        requireDebugAccess();
        ToolAccessGuard.requireAdminEndpoint("memory_debug_slot_write");
        String uid = ownUserId(request.getUserId());
        return Result.success(l2MemoryService.upsertSlotMemory(new L2MemoryWriteCommand(
                uid,
                request.getSessionId(),
                request.getMemoryType(),
                request.getContent(),
                request.getImportance(),
                request.getMetadataJson(),
                request.getSourceEventId(),
                request.getSourceType(),
                request.getAssertionStatus(),
                request.getScopeType(),
                request.getScopeKey(),
                request.getSlotKey())));
    }

    @DeleteMapping("/debug/slot")
    public Result<L2MemoryMutationResult> deleteSlot(@Valid @RequestBody L2MemorySlotDeleteRequestDTO request) {
        requireDebugAccess();
        ToolAccessGuard.requireAdminEndpoint("memory_debug_slot_delete");
        String uid = ownUserId(request.getUserId());
        return Result.success(l2MemoryService.deleteSlotMemory(new L2MemoryDeleteCommand(
                uid,
                request.getMemoryType(),
                request.getSourceEventId(),
                request.getScopeType(),
                request.getScopeKey(),
                request.getSlotKey())));
    }

    @GetMapping("/debug/query")
    public Result<List<L2MemoryHitVO>> query(@RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String sessionId,
                                              @RequestParam String q,
                                              @RequestParam(defaultValue = "5") Integer topK,
                                              @RequestParam(defaultValue = "0.4") Double threshold) {
        ToolAccessGuard.requireInternalEndpoint("memory_debug_query");
        requireDebugAccess();
        String uid = ownUserId(userId);
        String sid = StringUtils.hasText(sessionId) ? sessionId : "debug-session";
        List<L2MemoryHitVO> hits = l2MemoryService.retrieve(uid, sid, q, topK == null ? 5 : topK, threshold == null ? 0.4D : threshold);
        return Result.success(hits);
    }

    @GetMapping("/debug/recall")
    public Result<L2MemoryRecallResult> recall(@RequestParam(required = false) String userId,
                                                @RequestParam(required = false) String sessionId,
                                                @RequestParam String q,
                                                @RequestParam(required = false) List<String> memoryTypes,
                                                @RequestParam(defaultValue = "5") Integer topK,
                                                @RequestParam(defaultValue = "0.4") Double threshold,
                                                @RequestParam(defaultValue = "1000") Integer candidateLimit,
                                                @RequestParam(defaultValue = "false") boolean forceLexicalFallback) {
        ToolAccessGuard.requireInternalEndpoint("memory_debug_recall");
        requireDebugAccess();
        String uid = ownUserId(userId);
        String sid = StringUtils.hasText(sessionId) ? sessionId : "debug-session";
        return Result.success(l2MemoryService.recall(new L2MemoryRecallQuery(
                uid,
                sid,
                q,
                memoryTypes == null ? List.of() : memoryTypes,
                topK == null ? 5 : topK,
                threshold == null ? 0.4D : threshold,
                candidateLimit == null ? 1000 : candidateLimit,
                forceLexicalFallback)));
    }

    @GetMapping("/debug/profile")
    public Result<UserProfileVO> profile(@RequestParam(required = false) String userId,
                                         @RequestParam(required = false) String sessionId) {
        ToolAccessGuard.requireInternalEndpoint("memory_debug_profile");
        requireDebugAccess();
        String uid = ownUserId(userId);
        String sid = StringUtils.hasText(sessionId) ? sessionId : "debug-session";
        return Result.success(userProfileService.buildProfile(uid, sid));
    }

    private void requireDebugAccess() {
        if (!debugEnabled) {
            throw new BusinessException(ResultCode.FORBIDDEN, "记忆调试接口未启用");
        }
    }

    private String ownUserId(String requestedUserId) {
        String owner = ToolAccessGuard.requireAuthenticatedUserId().toString();
        if (StringUtils.hasText(requestedUserId) && !owner.equals(requestedUserId.trim())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能访问其他用户的记忆");
        }
        return owner;
    }
}
