package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit policy boundary for the runbook. The empty/default policy is disabled,
 * and every executable container/image pair must be declared here.
 */
public record DockerRestartPolicy(
        boolean enabled,
        Map<String, DockerServiceSpec> services,
        Duration commandTimeout,
        Duration healthTimeout,
        Duration healthPollInterval,
        int maxHealthAttempts) {

    public DockerRestartPolicy {
        services = copyServices(services);
        commandTimeout = positive(commandTimeout, "commandTimeout");
        healthTimeout = positive(healthTimeout, "healthTimeout");
        if (healthPollInterval == null || healthPollInterval.isNegative()) {
            throw new IllegalArgumentException("healthPollInterval must be non-negative");
        }
        if (maxHealthAttempts < 1 || maxHealthAttempts > 20) {
            throw new IllegalArgumentException("maxHealthAttempts must be between 1 and 20");
        }
    }

    public static DockerRestartPolicy disabled() {
        return new DockerRestartPolicy(false, Map.of(),
                Duration.ofSeconds(30), Duration.ofSeconds(3), Duration.ZERO, 3);
    }

    public DockerServiceSpec service(String containerName) {
        return services.get(containerName);
    }

    private static Map<String, DockerServiceSpec> copyServices(Map<String, DockerServiceSpec> input) {
        Map<String, DockerServiceSpec> copy = new LinkedHashMap<>();
        if (input != null) {
            input.forEach((name, spec) -> {
                if (name == null || spec == null || !name.equals(spec.containerName())) {
                    throw new IllegalArgumentException("service allowlist key must match containerName");
                }
                copy.put(name, spec);
            });
        }
        return Map.copyOf(copy);
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
