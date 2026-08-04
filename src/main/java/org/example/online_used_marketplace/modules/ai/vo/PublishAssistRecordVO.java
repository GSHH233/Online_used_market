package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class PublishAssistRecordVO {

    private String assistId;

    private Long userId;

    private String traceId;

    private String riskLevel;

    private Long productId;

    private String adoptionType;

    private List<String> adoptedFields = new ArrayList<>();

    private String feedbackReason;

    private String requestJson;

    private String suggestionJson;

    private String evidenceJson;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
