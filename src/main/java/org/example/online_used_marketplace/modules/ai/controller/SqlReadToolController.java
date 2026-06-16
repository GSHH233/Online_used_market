package org.example.online_used_marketplace.modules.ai.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.example.online_used_marketplace.modules.ai.dto.SqlReadRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.VectorDebugQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.SqlReadToolService;
import org.example.online_used_marketplace.modules.ai.service.VectorDebugToolService;
import org.example.online_used_marketplace.modules.ai.vo.SqlReadResultVO;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Admin
 * @ClassName: SqlReadToolController
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-05-17  23:08
 * @Version:
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/tools/mysql")
public class SqlReadToolController {
    private final SqlReadToolService sqlReadToolService;
    private final VectorDebugToolService vectorDebugToolService;
    @PostMapping("/read")
    public Result<ToolQueryResultVO> MysqlReadRequest(@Valid @RequestBody SqlReadRequestDTO dto) {
        ToolAccessGuard.requireInternal(dto.getMode(), "mysql_read");
        return Result.success(sqlReadToolService.executeReadOnly(dto));
    }
    @PostMapping("/milvus/read")
    public Result<ToolQueryResultVO> MilvusReadRequest(@Valid @RequestBody VectorDebugQueryRequestDTO dto) {
        ToolAccessGuard.requireInternal(dto.getMode(), "vector_debug");
        return Result.success(vectorDebugToolService.searchDebug(dto));
    }
}
