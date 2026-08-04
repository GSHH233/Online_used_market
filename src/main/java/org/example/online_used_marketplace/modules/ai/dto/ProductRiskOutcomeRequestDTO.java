package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProductRiskOutcomeRequestDTO {

    @NotBlank(message = "outcomeType不能为空")
    private String outcomeType;

    @NotBlank(message = "outcomeSource不能为空")
    private String outcomeSource;

    @Size(max = 64, message = "reasonCode不能超过64字")
    private String reasonCode;

    @Size(max = 1000, message = "原因不能超过1000字")
    private String reason;

    private Boolean isFinal = false;
}
