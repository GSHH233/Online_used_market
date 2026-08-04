package org.example.online_used_marketplace.modules.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
public class PublishAssistRecordQueryDTO {

    private Long userId;

    private Long productId;

    private String riskLevel;

    private String adoptionType;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime to;

    @Min(value = 1, message = "limit must be greater than 0")
    @Max(value = 100, message = "limit must be less than or equal to 100")
    private Integer limit = 20;
}
