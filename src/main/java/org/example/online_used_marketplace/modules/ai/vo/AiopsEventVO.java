package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiopsEventVO {

    private Long id;

    private String eventKey;

    private String traceId;

    private String severity;

    private String source;

    private String title;

    private String summary;

    private String status;

    private String metadataJson;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    private Long approvedBy;

    private LocalDateTime approvedAt;

    private LocalDateTime resolvedAt;

    private String resolutionType;

    private Boolean manualRequired;

    private String escalationReason;

    private List<AiopsActionVO> actions = new ArrayList<>();
}
