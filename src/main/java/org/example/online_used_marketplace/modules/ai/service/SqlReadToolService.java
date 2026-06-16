package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.SqlReadRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

/**
 * MySQL 只读工具服务
 */
public interface SqlReadToolService {

    ToolQueryResultVO executeReadOnly(SqlReadRequestDTO request);
}
