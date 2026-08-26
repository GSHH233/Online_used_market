package org.example.online_used_marketplace.modules.ai.alert;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiopsAlertWebhookVO {

    private Long deliveryId;

    private boolean duplicate;

    private int acceptedAlerts;

    private List<Long> eventIds = new ArrayList<>();

    private String traceId;

    private String payloadHash;

    private String processingStatus;
}
