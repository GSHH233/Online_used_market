package org.example.online_used_marketplace.modules.ai.agent.tools;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.service.UserProfileService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.example.online_used_marketplace.modules.ai.vo.UserProfileVO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ProfileAgentTool implements AgentTool {

    private final UserProfileService userProfileService;

    @Override
    public String name() {
        return "profile";
    }

    @Override
    public String description() {
        return "读取 L2 聚合出的结构化用户 Profile。";
    }

    @Override
    public boolean internalOnly() {
        return false;
    }

    @Override
    public ToolQueryResultVO execute(Map<String, Object> input) {
        long start = System.currentTimeMillis();
        String userId = input == null || input.get("userId") == null ? "" : String.valueOf(input.get("userId"));
        if (!StringUtils.hasText(userId) || "anonymous".equalsIgnoreCase(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "Profile 查询缺少认证身份");
        }
        String sessionId = String.valueOf(input.getOrDefault("sessionId", ""));
        UserProfileVO profile = userProfileService.buildProfile(userId, sessionId);
        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool(name());
        vo.setTarget("userId=" + userId + ",sessionId=" + sessionId);
        vo.setLimit(1);
        vo.setTotal(1L);
        vo.setTruncated(false);
        vo.setElapsedMs(System.currentTimeMillis() - start);
        vo.setColumns(List.of("promptBlock", "rawPreferences"));
        vo.setRows(List.of(Map.of(
                "promptBlock", profile.getPromptBlock(),
                "rawPreferences", profile.getRawPreferences()
        )));
        vo.setSummary("profile读取完成");
        return vo;
    }
}
