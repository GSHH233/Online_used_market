package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentRuntimeStartRequestDTO {
    @NotBlank(message = "问题不能为空")
    private String question;
    private String sessionId;
    @Min(1) @Max(8)
    private Integer maxSteps = 6;
    @Min(0) @Max(8)
    private Integer failAfterNodeCount = 0;
}
