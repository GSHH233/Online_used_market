package org.example.online_used_marketplace.modules.ai.aiops.demo.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiopsDemoServiceStateVO {

    private String service;

    private Integer health;

    private Double errorRatio;

    private Boolean faulted;

    private String traceId;

    private LocalDateTime updatedAt;
}
