package org.example.online_used_marketplace.modules.ai.alert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AlertmanagerAlertDTO {

    @NotBlank
    @Size(max = 32)
    private String status;

    @Size(max = 32)
    private Map<String, String> labels = new LinkedHashMap<>();

    @Size(max = 32)
    private Map<String, String> annotations = new LinkedHashMap<>();

    @Size(max = 64)
    private String startsAt;

    @Size(max = 64)
    private String endsAt;

    @Size(max = 1024)
    private String generatorURL;

    @Size(max = 128)
    private String fingerprint;
}
