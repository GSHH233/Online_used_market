package org.example.online_used_marketplace.modules.ai.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AiopsEventCreateDTO {

    private String eventKey;

    private String traceId;

    private String severity = "INFO";

    private String source = "manual";

    private String title;

    private String summary;

    private String suggestion;

    private Map<String, Object> metadata;
}
