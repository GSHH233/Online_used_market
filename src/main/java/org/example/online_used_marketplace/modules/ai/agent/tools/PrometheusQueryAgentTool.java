package org.example.online_used_marketplace.modules.ai.agent.tools;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.dto.PrometheusQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.PrometheusToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class PrometheusQueryAgentTool implements AgentTool {

    private final PrometheusToolService prometheusToolService;

    @Override
    public String name() {
        return "prometheus_query";
    }

    @Override
    public String description() {
        return "查询 Prometheus 指标，用于 AIOps 延迟、错误率、CPU、内存、连接池等诊断。";
    }

    @Override
    public boolean internalOnly() {
        return true;
    }

    @Override
    public ToolQueryResultVO execute(Map<String, Object> input) {
        PrometheusQueryRequestDTO dto = new PrometheusQueryRequestDTO();
        dto.setQuery(String.valueOf(input.getOrDefault("query", "up")));
        dto.setQueryType(String.valueOf(input.getOrDefault("queryType", "instant")));
        dto.setTime(input.get("time") == null ? null : String.valueOf(input.get("time")));
        dto.setStart(input.get("start") == null ? null : String.valueOf(input.get("start")));
        dto.setEnd(input.get("end") == null ? null : String.valueOf(input.get("end")));
        dto.setStep(input.get("step") == null ? null : String.valueOf(input.get("step")));
        dto.setLimit(input.get("limit") instanceof Number n ? n.intValue() : 20);
        return prometheusToolService.query(dto);
    }
}
