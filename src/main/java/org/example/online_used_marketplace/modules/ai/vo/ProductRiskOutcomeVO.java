package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProductRiskOutcomeVO {

    private Long id;

    private Long productId;

    private String assistId;

    private String outcomeType;

    private String outcomeSource;

    private String reasonCode;

    private String reason;

    private Long operatorId;

    private Boolean isFinal;

    private LocalDateTime createdTime;
}
