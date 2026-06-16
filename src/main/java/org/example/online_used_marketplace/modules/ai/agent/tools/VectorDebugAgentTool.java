package org.example.online_used_marketplace.modules.ai.agent.tools;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.dto.VectorDebugQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.VectorDebugToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class VectorDebugAgentTool implements AgentTool {

    private final VectorDebugToolService vectorDebugToolService;

    @Override
    public String name() {
        return "vector_debug";
    }

    @Override
    public String description() {
        return "调试知识库向量/混合检索召回结果。";
    }

    @Override
    public boolean internalOnly() {
        return true;
    }

    @Override
    public ToolQueryResultVO execute(Map<String, Object> input) {
        VectorDebugQueryRequestDTO dto = new VectorDebugQueryRequestDTO();
        dto.setQuestion(String.valueOf(input.getOrDefault("question", "")));
        dto.setTopK(input.get("topK") instanceof Number n ? n.intValue() : 5);
        return vectorDebugToolService.searchDebug(dto);
    }
}
