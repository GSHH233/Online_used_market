package org.example.online_used_marketplace.modules.ai.alert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AiopsChangeEventCreateDTO {

    @NotBlank
    @Size(max = 128)
    private String service;

    @Size(max = 64)
    private String environment;

    @NotBlank
    @Size(max = 64)
    private String changeType;

    @Size(max = 128)
    private String version;

    @Size(max = 128)
    private String traceId;

    @Size(max = 4000)
    private String summary;

    private LocalDateTime occurredAt;

    private Map<String, String> metadata = new LinkedHashMap<>();
}
