package org.example.online_used_marketplace.modules.ai.controller;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentExecutionIdentity;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContext;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContextBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/agent/runtime-context")
@RequiredArgsConstructor
public class AgentRuntimeContextDebugController {

    private final AgentRuntimeContextBuilder contextBuilder;

    @Value("${ai.memory.debug.enabled:false}")
    private boolean debugEnabled;

    @GetMapping("/debug")
    public Result<AgentRuntimeContext> build(@RequestParam String sessionId,
                                             @RequestParam String q) {
        if (!debugEnabled) {
            throw new BusinessException(ResultCode.FORBIDDEN, "运行时上下文调试接口未启用");
        }
        ToolAccessGuard.requireAdminEndpoint("agent_runtime_context_debug");
        AgentExecutionIdentity identity = AgentExecutionIdentity.current();
        return Result.success(contextBuilder.build(
                "memory-eval", sessionId, "memory-eval-context", "INTERNAL", q, 4, identity));
    }
}
