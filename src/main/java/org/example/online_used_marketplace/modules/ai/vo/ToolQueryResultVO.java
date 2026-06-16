package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具查询通用返回结构
 */
@Data
public class ToolQueryResultVO {

    /**
     * 工具名称：mysql_read / vector_debug / log_query ...
     */
    private String tool;

    /**
     * 查询目标说明，例如 docId、traceId、question
     */
    private String target;

    /**
     * 限制条数
     */
    private Integer limit;

    /**
     * 返回总数
     */
    private Long total;

    /**
     * 是否截断
     */
    private Boolean truncated;

    /**
     * 查询耗时（毫秒）
     */
    private Long elapsedMs;

    /**
     * 列名（适用于 tabular 场景）
     */
    private List<String> columns = new ArrayList<>();

    /**
     * 原始结果集（通用载荷）
     */
    private List<Map<String, Object>> rows = new ArrayList<>();

    /**
     * 结构化说明（可选）
     */
    private String summary;
}
