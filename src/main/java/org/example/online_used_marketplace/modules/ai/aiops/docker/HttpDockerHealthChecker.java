package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Default HTTP health checker used by the isolated Docker runbook. */
public final class HttpDockerHealthChecker implements DockerHealthChecker {

    private final HttpClient httpClient;

    public HttpDockerHealthChecker() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    public HttpDockerHealthChecker(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public DockerHealthResult check(URI healthUrl, Duration timeout) {
        Objects.requireNonNull(healthUrl, "healthUrl");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long startedAt = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(healthUrl)
                .timeout(timeout)
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 300
                    ? DockerHealthResult.healthy(status, elapsedMs(startedAt))
                    : DockerHealthResult.unhealthy(status, "HTTP status " + status, elapsedMs(startedAt));
        } catch (IOException ex) {
            return DockerHealthResult.unhealthy(0, ex.getClass().getSimpleName() + ": " + safeMessage(ex),
                    elapsedMs(startedAt));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return DockerHealthResult.unhealthy(0, "health check interrupted", elapsedMs(startedAt));
        }
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
