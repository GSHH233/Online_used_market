package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.enums.ToolRouteMode;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

/**
 * 工具路由服务（MVP）
 */
public interface ToolRouteService {

    ToolQueryResultVO route(String question, String traceId, ToolRouteMode mode);
}
