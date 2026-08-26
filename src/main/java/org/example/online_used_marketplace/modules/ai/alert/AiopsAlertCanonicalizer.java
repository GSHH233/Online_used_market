package org.example.online_used_marketplace.modules.ai.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

public final class AiopsAlertCanonicalizer {

    private AiopsAlertCanonicalizer() {
    }

    public static String fingerprint(String source, Map<String, String> labels) {
        Map<String, String> trusted = new TreeMap<>();
        trusted.put("source", value(source));
        trusted.put("alertname", value(labels == null ? null : labels.get("alertname")));
        trusted.put("service", value(labels == null ? null : labels.get("service")));
        trusted.put("job", value(labels == null ? null : labels.get("job")));
        trusted.put("instance", value(labels == null ? null : labels.get("instance")));
        trusted.put("namespace", value(labels == null ? null : labels.get("namespace")));
        trusted.put("cluster", value(labels == null ? null : labels.get("cluster")));
        return sha256(canonicalPairs(trusted));
    }

    public static String payloadHash(AlertmanagerWebhookDTO webhook, ObjectMapper objectMapper) {
        try {
            return sha256(objectMapper.writeValueAsString(canonicalPayload(webhook)));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("告警 payload 无法规范化", ex);
        }
    }

    static Map<String, Object> canonicalPayload(AlertmanagerWebhookDTO webhook) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("alerts", webhook.getAlerts());
        payload.put("commonAnnotations", sorted(webhook.getCommonAnnotations()));
        payload.put("commonLabels", sorted(webhook.getCommonLabels()));
        payload.put("externalURL", value(webhook.getExternalURL()));
        payload.put("groupKey", value(webhook.getGroupKey()));
        payload.put("groupLabels", sorted(webhook.getGroupLabels()));
        payload.put("receiver", value(webhook.getReceiver()));
        payload.put("status", value(webhook.getStatus()));
        payload.put("version", value(webhook.getVersion()));
        return payload;
    }

    static Map<String, String> sorted(Map<String, String> value) {
        return value == null ? Map.of() : new TreeMap<>(value);
    }

    static String canonicalPairs(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        values.forEach((key, value) -> builder.append(key).append('=').append(value(value)).append('|'));
        return builder.toString();
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }

    private static String value(String input) {
        return input == null ? "" : input.trim();
    }
}
