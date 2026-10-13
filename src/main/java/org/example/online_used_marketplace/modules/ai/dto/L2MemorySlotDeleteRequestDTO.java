package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class L2MemorySlotDeleteRequestDTO {

    private String userId;

    @NotBlank
    private String memoryType;

    @NotBlank
    private String sourceEventId;

    private String scopeType;
    private String scopeKey;

    @NotBlank
    private String slotKey;
}
