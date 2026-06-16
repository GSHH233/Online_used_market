package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PrometheusQueryRequestDTO {

    @NotBlank(message = "PromQL不能为空")
    @Size(max = 1000, message = "PromQL长度不能超过1000")
    private String query;

    /** instant 或 range。 */
    private String queryType = "instant";

    /** Prometheus instant query 的 time 参数，可为空。 */
    private String time;

    /** range query 的 start 参数，可为空，为空时按 defaultRangeMinutes 推导。 */
    private String start;

    /** range query 的 end 参数，可为空，为空时使用当前时间。 */
    private String end;

    /** range query 的 step 参数，例如 30s / 1m / 5m。 */
    private String step;

    @Min(value = 1, message = "limit最小为1")
    @Max(value = 500, message = "limit最大为500")
    private Integer limit;

    /**
     * USER / INTERNAL. Direct metrics query is only allowed in INTERNAL mode.
     */
    private String mode = "USER";
}
