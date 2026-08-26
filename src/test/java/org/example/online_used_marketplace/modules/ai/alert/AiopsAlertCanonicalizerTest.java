package org.example.online_used_marketplace.modules.ai.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AiopsAlertCanonicalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fingerprintIgnoresLabelInsertionOrderAndUntrustedAlertmanagerFingerprint() {
        LinkedHashMap<String, String> first = new LinkedHashMap<>();
        first.put("alertname", "HighErrorRate");
        first.put("service", "marketplace-api");
        first.put("instance", "api-1");

        LinkedHashMap<String, String> second = new LinkedHashMap<>();
        second.put("instance", "api-1");
        second.put("service", "marketplace-api");
        second.put("alertname", "HighErrorRate");

        assertEquals(
                AiopsAlertCanonicalizer.fingerprint("alertmanager", first),
                AiopsAlertCanonicalizer.fingerprint("alertmanager", second));
        assertEquals(64, AiopsAlertCanonicalizer.fingerprint("alertmanager", first).length());
    }

    @Test
    void payloadHashIsStableForMapInsertionOrderButChangesForPayloadData() {
        AlertmanagerWebhookDTO first = webhook("HighErrorRate", "external-1");
        AlertmanagerWebhookDTO second = webhook("HighErrorRate", "external-1");
        second.getCommonLabels().clear();
        second.getCommonLabels().put("service", "marketplace-api");
        second.getCommonLabels().put("alertname", "HighErrorRate");

        assertEquals(
                AiopsAlertCanonicalizer.payloadHash(first, objectMapper),
                AiopsAlertCanonicalizer.payloadHash(second, objectMapper));

        second.setExternalURL("external-2");
        assertNotEquals(
                AiopsAlertCanonicalizer.payloadHash(first, objectMapper),
                AiopsAlertCanonicalizer.payloadHash(second, objectMapper));
    }

    private AlertmanagerWebhookDTO webhook(String alertName, String externalUrl) {
        AlertmanagerWebhookDTO webhook = new AlertmanagerWebhookDTO();
        webhook.setReceiver("aiops");
        webhook.setStatus("firing");
        webhook.setGroupKey("group-1");
        webhook.setExternalURL(externalUrl);
        webhook.getCommonLabels().put("alertname", alertName);
        webhook.getCommonLabels().put("service", "marketplace-api");
        AlertmanagerAlertDTO alert = new AlertmanagerAlertDTO();
        alert.setStatus("firing");
        alert.setLabels(new LinkedHashMap<>());
        alert.getLabels().put("instance", "api-1");
        alert.setAnnotations(new LinkedHashMap<>());
        webhook.setAlerts(List.of(alert));
        return webhook;
    }
}
