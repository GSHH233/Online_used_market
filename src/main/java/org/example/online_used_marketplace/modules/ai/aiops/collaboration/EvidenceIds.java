package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class EvidenceIds {

    private EvidenceIds() {
    }

    static String forSpecialist(SpecialistType type, AlertInvestigationContext context) {
        String identity = context == null ? "empty" : firstNonBlank(
                context.getFingerprint(), context.getEventId(), context.getTraceId(), "anonymous");
        return type.name().toLowerCase() + "-" + shortHash(identity);
    }

    static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(12);
            for (int i = 0; i < 6 && i < bytes.length; i++) {
                out.append(String.format("%02x", bytes[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "anonymous";
    }
}
