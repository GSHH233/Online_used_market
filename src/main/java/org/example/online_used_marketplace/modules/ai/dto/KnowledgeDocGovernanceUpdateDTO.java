package org.example.online_used_marketplace.modules.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeDocGovernanceUpdateDTO {

    private String sourceTrustLevel;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    private String docVersion;

    private String status;

    private String disabledReason;
}
