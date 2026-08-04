package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KnowledgeGapUnresolvedFeedbackDTO {
    @NotBlank(message = "query不能为空")
    @Size(max = 2000, message = "query不能超过2000字")
    private String query;
    @Size(max = 128)
    private String sessionId;
    @Size(max = 128)
    private String traceId;
}
