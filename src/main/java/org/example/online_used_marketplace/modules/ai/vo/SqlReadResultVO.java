package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MySQL 只读工具响应
 */
@Data
public class SqlReadResultVO {

    private String sql;
    private Integer limit;
    private Long rowCount;
    private Boolean truncated;
    private Long elapsedMs;

    private List<String> columns = new ArrayList<>();
    private List<Map<String, Object>> rows = new ArrayList<>();
}
