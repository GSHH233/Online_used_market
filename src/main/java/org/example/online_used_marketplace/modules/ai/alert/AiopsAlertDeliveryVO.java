package org.example.online_used_marketplace.modules.ai.alert;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiopsAlertDeliveryVO {

    private Long id;

    private String deliveryKey;

    private String payloadHash;

    private String groupKey;

    private String status;

    private Integer alertCount;

    private String processingStatus;

    private String errorMsg;

    private String traceId;

    private List<Long> eventIds = new ArrayList<>();

    private String payloadJson;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
