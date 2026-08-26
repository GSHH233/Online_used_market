package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class ControlledOperationPlanRequestDTO {

    @NotBlank(message = "actionCode不能为空")
    private String actionCode;

    private Map<String, Object> params;

    private String reason;

    private String traceId;

    /** Optional alert/event that caused this controlled operation. */
    private Long sourceEventId;

    /** Optional diagnosis fingerprint used for stable plan idempotency. */
    private String diagnosisHash;
}
