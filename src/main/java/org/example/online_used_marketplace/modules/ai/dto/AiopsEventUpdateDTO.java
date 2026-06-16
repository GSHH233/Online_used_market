package org.example.online_used_marketplace.modules.ai.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AiopsEventUpdateDTO {

    private String status;

    private String summary;

    private String suggestion;

    private Boolean executed;

    private Map<String, Object> metadata;
}
