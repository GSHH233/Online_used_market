package org.example.online_used_marketplace.modules.ai.aiops.demo.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiopsDemoFaultRequestDTO {

    @NotBlank(message = "service不能为空")
    private String service;

    @DecimalMin(value = "0.0", message = "errorRatio不能小于0")
    @DecimalMax(value = "1.0", message = "errorRatio不能大于1")
    private Double errorRatio = 1.0D;

    private String traceId;
}
