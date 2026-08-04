package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentInvestigationRequestDTO {

    private String sessionId;

    private String traceId;

    @NotBlank(message = "问题不能为空")
    private String question;

    @Min(value = 1, message = "maxSteps最小为1")
    @Max(value = 8, message = "maxSteps最大为8")
    private Integer maxSteps = 6;
}
