package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.WsChatStartRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.RagChatService;
import org.example.online_used_marketplace.modules.ai.vo.WsChatDoneVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * RAG Chat 评测入口。业务主通道仍是 WebSocket，此接口用于自动化回归。
 */
@RestController
@RequestMapping("/api/ai/rag-chat")
@RequiredArgsConstructor
public class RagChatEvalController {

    private final RagChatService ragChatService;

    @PostMapping("/eval")
    public Result<WsChatDoneVO> eval(@Valid @RequestBody WsChatStartRequestDTO dto) {
        String traceId = UUID.randomUUID().toString();
        String sessionId = StringUtils.hasText(dto.getRequestId()) ? "eval-" + dto.getRequestId() : "eval-" + traceId;
        WsChatDoneVO done = ragChatService.chat(sessionId, dto.getQuestion(), traceId, dto.getTopK(), dto.getFilter());
        if (done != null) {
            done.setRequestId(dto.getRequestId());
            done.setTraceId(traceId);
        }
        return Result.success(done);
    }
}
