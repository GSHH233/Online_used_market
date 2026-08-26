package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.net.URI;
import java.util.Objects;

/** Immutable allowlisted service definition used for restart and rollback. */
public record DockerServiceSpec(
        String containerName,
        String rollbackImage,
        URI healthUrl,
        int hostPort,
        int containerPort,
        int restartGraceSeconds) {

    public DockerServiceSpec {
        containerName = requireContainerName(containerName);
        rollbackImage = requireImage(rollbackImage);
        healthUrl = requireHealthUrl(healthUrl);
        if (hostPort < 1 || hostPort > 65535 || containerPort < 1 || containerPort > 65535) {
            throw new IllegalArgumentException("ports must be between 1 and 65535");
        }
        if (restartGraceSeconds < 0 || restartGraceSeconds > 300) {
            throw new IllegalArgumentException("restartGraceSeconds must be between 0 and 300");
        }
    }

    private static String requireContainerName(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("container name is not allowlist-safe");
        }
        return value;
    }

    private static String requireImage(String value) {
        if (value == null || value.isBlank() || value.length() > 255
                || value.chars().anyMatch(Character::isWhitespace)
                || value.indexOf('\u0000') >= 0
                || value.indexOf(';') >= 0 || value.indexOf('|') >= 0
                || value.indexOf('&') >= 0 || value.indexOf('`') >= 0) {
            throw new IllegalArgumentException("rollback image is not allowlist-safe");
        }
        return value;
    }

    private static URI requireHealthUrl(URI value) {
        Objects.requireNonNull(value, "healthUrl");
        String scheme = value.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || value.getHost() == null || value.getUserInfo() != null) {
            throw new IllegalArgumentException("healthUrl must be an HTTP(S) URL without user info");
        }
        return value;
    }
}
