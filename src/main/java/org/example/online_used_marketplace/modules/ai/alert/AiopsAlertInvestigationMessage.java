package org.example.online_used_marketplace.modules.ai.alert;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiopsAlertInvestigationMessage implements Serializable {

    private Long deliveryId;

    private Long eventId;

    private String fingerprint;

    private String correlationKey;

    private String traceId;

    private String payloadHash;

    private LocalDateTime publishedAt;
}
