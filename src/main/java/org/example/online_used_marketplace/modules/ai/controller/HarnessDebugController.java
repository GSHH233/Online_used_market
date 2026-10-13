package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.agent.AgentToolRegistry;
import org.example.online_used_marketplace.modules.ai.agent.harness.ToolExecutionHarness;
import org.example.online_used_marketplace.modules.ai.agent.harness.ToolExecutionResult;
import org.example.online_used_marketplace.modules.ai.dto.HarnessDebugExecuteRequestDTO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/harness/debug")
public class HarnessDebugController {

    private final AgentToolRegistry toolRegistry;
    private final ToolExecutionHarness toolExecutionHarness;

    @PostMapping("/execute")
    public Result<Map<String, Object>> execute(@Valid @RequestBody HarnessDebugExecuteRequestDTO request) {
        ToolAccessGuard.requireInternalEndpoint("harness_debug_execute");
        AgentTool tool = toolRegistry.find(request.getTool())
                .orElseThrow(() -> new BusinessException(ResultCode.BAD_REQUEST, "未知工具：" + request.getTool()));
        String mode = StringUtils.hasText(request.getMode()) ? request.getMode().trim().toUpperCase() : "USER";
        if (tool.internalOnly() && !"INTERNAL".equals(mode)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "USER模式不允许调用内部工具：" + tool.name());
        }
        if ("INTERNAL".equals(mode)) {
            ToolAccessGuard.requireInternal(mode, tool.name());
        }
        Map<String, Object> input = request.getInput() == null ? Map.of() : request.getInput();
        if ("profile".equals(tool.name())) {
            String owner = ToolAccessGuard.requireAuthenticatedUserId().toString();
            Object requested = input.get("userId");
            if (requested != null && !owner.equals(String.valueOf(requested))) {
                throw new BusinessException(ResultCode.FORBIDDEN, "不能查询其他用户的 Profile");
            }
            Map<String, Object> scopedInput = new LinkedHashMap<>(input);
            scopedInput.put("userId", owner);
            input = scopedInput;
        }
        ToolExecutionResult result = toolExecutionHarness.execute(tool, input, mode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", result.getTool());
        data.put("status", result.getStatus());
        data.put("observation", result.getObservation());
        data.put("elapsedMs", result.getElapsedMs());
        data.put("attempts", result.getAttempts());
        data.put("retryCount", result.getRetryCount());
        data.put("timeout", result.isTimeout());
        data.put("fallbackUsed", result.isFallbackUsed());
        data.put("snapshotRestored", result.isSnapshotRestored());
        data.put("errorMessage", result.getErrorMessage());
        data.put("metadata", result.metadata());
        return Result.success(data);
    }
}
