package org.example.online_used_marketplace.modules.ai.agent.tools;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.dto.LogQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.LogToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class LogQueryAgentTool implements AgentTool {

    private final LogToolService logToolService;

    @Override
    public String name() {
        return "log_query";
    }

    @Override
    public String description() {
        return "按 traceId 查询应用日志。";
    }

    @Override
    public boolean internalOnly() {
        return true;
    }

    @Override
    public ToolQueryResultVO execute(Map<String, Object> input) {
        LogQueryRequestDTO dto = new LogQueryRequestDTO();
        dto.setTraceId(String.valueOf(input.getOrDefault("traceId", "")));
        dto.setService(input.get("service") == null ? null : String.valueOf(input.get("service")));
        dto.setLimit(input.get("limit") instanceof Number n ? n.intValue() : 50);
        dto.setWindowMinutes(input.get("windowMinutes") instanceof Number n ? n.intValue() : 60);
        return logToolService.queryByTraceId(dto);
    }
}
