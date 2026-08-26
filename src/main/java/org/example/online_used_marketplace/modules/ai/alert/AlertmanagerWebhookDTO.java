package org.example.online_used_marketplace.modules.ai.alert;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AlertmanagerWebhookDTO {

    @NotBlank
    @Size(max = 160)
    private String receiver;

    @NotBlank
    @Size(max = 32)
    private String status;

    @Size(max = 160)
    private String groupKey;

    @Size(max = 32)
    private Map<String, String> groupLabels = new LinkedHashMap<>();

    @Size(max = 32)
    private Map<String, String> commonLabels = new LinkedHashMap<>();

    @Size(max = 32)
    private Map<String, String> commonAnnotations = new LinkedHashMap<>();

    @Size(max = 512)
    private String externalURL;

    @Size(max = 32)
    private String version;

    @NotNull
    @Valid
    @Size(max = 100)
    private List<AlertmanagerAlertDTO> alerts;
}
