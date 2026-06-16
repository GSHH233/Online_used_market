package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 日志查询工具请求
 */
@Data
public class LogQueryRequestDTO {

    @NotBlank(message = "traceId不能为空")
    private String traceId;

    private String service;

    @Min(value = 1, message = "limit最小为1")
    @Max(value = 500, message = "limit最大为500")
    private Integer limit = 100;

    /**
     * 时间窗，单位分钟（可选）
     */
    @Min(value = 1, message = "windowMinutes最小为1")
    @Max(value = 1440, message = "windowMinutes最大为1440")
    private Integer windowMinutes;
}
