package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.PrometheusQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.PrometheusToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/tools/prometheus")
public class PrometheusToolController {

    private final PrometheusToolService prometheusToolService;

    @PostMapping("/query")
    public Result<ToolQueryResultVO> query(@Valid @RequestBody PrometheusQueryRequestDTO request) {
        ToolAccessGuard.requireInternal(request.getMode(), "prometheus_query");
        return Result.success(prometheusToolService.query(request));
    }
}
