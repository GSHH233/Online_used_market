package org.example.online_used_marketplace.modules.ai.controller;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.service.UserProfileService;
import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;
import org.example.online_used_marketplace.modules.ai.vo.UserProfileVO;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai/l2-memory")
@RequiredArgsConstructor
public class L2MemoryDebugController {

    private final L2MemoryService l2MemoryService;
    private final UserProfileService userProfileService;

    @PostMapping("/debug/write")
    public Result<String> write(@RequestParam(required = false) String userId,
                                @RequestParam(required = false) String sessionId,
                                @RequestParam(required = false) String memoryType,
                                @RequestParam String content,
                                @RequestParam(required = false) Double importance,
                                @RequestParam(required = false) String metadataJson) {
        String uid = StringUtils.hasText(userId) ? userId : "debug-user";
        String sid = StringUtils.hasText(sessionId) ? sessionId : "debug-session";
        String type = StringUtils.hasText(memoryType) ? memoryType : "FACT";
        double imp = importance == null ? 0.6D : importance;

        l2MemoryService.saveMemory(uid, sid, type, content, imp, metadataJson);
        return Result.success("ok");
    }

    @GetMapping("/debug/query")
    public Result<List<L2MemoryHitVO>> query(@RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String sessionId,
                                              @RequestParam String q,
                                              @RequestParam(defaultValue = "5") Integer topK,
                                              @RequestParam(defaultValue = "0.4") Double threshold) {
        String uid = StringUtils.hasText(userId) ? userId : "debug-user";
        String sid = StringUtils.hasText(sessionId) ? sessionId : "debug-session";
        List<L2MemoryHitVO> hits = l2MemoryService.retrieve(uid, sid, q, topK == null ? 5 : topK, threshold == null ? 0.4D : threshold);
        return Result.success(hits);
    }

    @GetMapping("/debug/profile")
    public Result<UserProfileVO> profile(@RequestParam(required = false) String userId,
                                         @RequestParam(required = false) String sessionId) {
        String uid = StringUtils.hasText(userId) ? userId : "debug-user";
        String sid = StringUtils.hasText(sessionId) ? sessionId : "debug-session";
        return Result.success(userProfileService.buildProfile(uid, sid));
    }
}
