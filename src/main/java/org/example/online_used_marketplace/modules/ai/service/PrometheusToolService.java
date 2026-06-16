package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.PrometheusQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

public interface PrometheusToolService {

    ToolQueryResultVO query(PrometheusQueryRequestDTO request);
}
