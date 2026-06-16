package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.LogQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

/**
 * 日志查询工具服务
 */
public interface LogToolService {

    ToolQueryResultVO queryByTraceId(LogQueryRequestDTO request);
}
