package org.example.online_used_marketplace.modules.ai.alert;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/aiops")
public class AiopsAlertController {

    private static final String WEBHOOK_TOKEN_HEADER = "X-AIOPS-Webhook-Token";

    private final AiopsAlertService aiopsAlertService;

    @PostMapping("/alerts/alertmanager")
    public Result<AiopsAlertWebhookVO> alertmanager(
            @Valid @RequestBody AlertmanagerWebhookDTO request,
            @RequestHeader(value = WEBHOOK_TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return Result.success(aiopsAlertService.receive(request, webhookToken(token, authorization)));
    }

    @GetMapping("/alerts/deliveries/{deliveryId}")
    public Result<AiopsAlertDeliveryVO> delivery(@PathVariable Long deliveryId) {
        AiopsAlertAccessGuard.requireInternal("aiops_alert_delivery_detail");
        return Result.success(aiopsAlertService.getDelivery(deliveryId));
    }

    @PostMapping("/changes")
    public Result<AiopsChangeEventVO> change(@Valid @RequestBody AiopsChangeEventCreateDTO request) {
        AiopsAlertAccessGuard.requireInternal("aiops_change_event_create");
        return Result.success(aiopsAlertService.createChangeEvent(request));
    }

    private String webhookToken(String explicitToken, String authorization) {
        if (explicitToken != null && !explicitToken.isBlank()) {
            return explicitToken;
        }
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return "";
    }
}
