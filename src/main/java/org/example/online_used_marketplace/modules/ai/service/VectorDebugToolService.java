package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.VectorDebugQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

/**
 * 向量检索调试工具服务
 */
public interface VectorDebugToolService {

    ToolQueryResultVO debugByDocId(Long docId, Integer limit);

    ToolQueryResultVO searchDebug(VectorDebugQueryRequestDTO request);
}
