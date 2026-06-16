package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 日志查询工具结果
 */
@Data
public class LogQueryResultVO {
    private String traceId;
    private String service;
    private Integer limit;
    private Integer windowMinutes;
    private Long total;
    private Boolean truncated;
    private List<LogLineVO> lines = new ArrayList<>();
}
