package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class L2MemorySlotWriteRequestDTO {

    private String userId;
    private String sessionId;

    @NotBlank
    private String memoryType;

    @NotBlank
    private String content;

    private Double importance;
    private String metadataJson;

    @NotBlank
    private String sourceEventId;

    private String sourceType;
    private String assertionStatus;
    private String scopeType;
    private String scopeKey;

    @NotBlank
    private String slotKey;
}
