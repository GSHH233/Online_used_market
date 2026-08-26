package org.example.online_used_marketplace.modules.ai.aiops.docker;

/** Structured HTTP health-check result. */
public record DockerHealthResult(
        boolean healthy,
        int statusCode,
        String error,
        long elapsedMs) {

    public static DockerHealthResult healthy(int statusCode, long elapsedMs) {
        return new DockerHealthResult(true, statusCode, "", elapsedMs);
    }

    public static DockerHealthResult unhealthy(int statusCode, String error, long elapsedMs) {
        return new DockerHealthResult(false, statusCode, error == null ? "" : error, elapsedMs);
    }
}
