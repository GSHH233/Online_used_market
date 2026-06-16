package org.example.online_used_marketplace.modules.ai.agent.tools;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.agent.AgentTool;
import org.example.online_used_marketplace.modules.ai.dto.SqlReadRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.SqlReadToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class SqlReadAgentTool implements AgentTool {

    private final SqlReadToolService sqlReadToolService;

    @Override
    public String name() {
        return "mysql_read";
    }

    @Override
    public String description() {
        return "执行白名单内 MySQL SELECT 只读查询。";
    }

    @Override
    public boolean internalOnly() {
        return true;
    }

    @Override
    public ToolQueryResultVO execute(Map<String, Object> input) {
        SqlReadRequestDTO dto = new SqlReadRequestDTO();
        dto.setSql(String.valueOf(input.getOrDefault("sql", "select id, title, status, created_time from ai_knowledge_doc order by id desc")));
        dto.setLimit(input.get("limit") instanceof Number n ? n.intValue() : 20);
        return sqlReadToolService.executeReadOnly(dto);
    }
}
