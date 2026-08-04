package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class KnowledgeGapLinkDocDTO {
    @NotNull(message = "docId不能为空")
    private Long docId;
    private Long taskId;
}
