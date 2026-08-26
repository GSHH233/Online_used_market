package org.example.online_used_marketplace.modules.ai.aiops.docker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DockerRunbookProperties.class)
public class DockerRunbookConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "ai.aiops.docker-runbook", name = "enabled", havingValue = "true")
    public DockerRestartRunbook dockerRestartRunbook(DockerRunbookProperties properties) {
        DockerServiceSpec spec = new DockerServiceSpec(
                properties.getContainerName(), properties.getRollbackImage(), properties.getHealthUrl(),
                properties.getHostPort(), properties.getContainerPort(), properties.getRestartGraceSeconds());
        Map<String, DockerServiceSpec> specs = Map.of(spec.containerName(), spec);
        DockerCommandExecutor commandExecutor = commandExecutor(properties, spec, specs);
        DockerRestartPolicy policy = new DockerRestartPolicy(true, specs,
                positive(properties.getCommandTimeoutMs(), "commandTimeoutMs"),
                positive(properties.getHealthTimeoutMs(), "healthTimeoutMs"),
                nonNegative(properties.getHealthPollIntervalMs(), "healthPollIntervalMs"),
                properties.getMaxHealthAttempts());
        DockerRestartRunbookExecutor executor = new DockerRestartRunbookExecutor(
                commandExecutor, new HttpDockerHealthChecker(), policy);
        return new IsolatedDockerRestartRunbook(
                properties.getTargetId(), spec, executor, properties.getTransport());
    }

    private DockerCommandExecutor commandExecutor(DockerRunbookProperties properties,
                                                  DockerServiceSpec spec,
                                                  Map<String, DockerServiceSpec> specs) {
        String transport = properties.getTransport() == null
                ? "" : properties.getTransport().trim().toLowerCase(Locale.ROOT);
        return switch (transport) {
            case "local-cli" -> new ProcessDockerCommandExecutor(properties.getDockerExecutable());
            case "ssh-helper" -> new SshDockerHelperCommandExecutor(
                    properties.getSsh().getExecutable(),
                    properties.getSsh().getHost(),
                    properties.getSsh().getPort(),
                    properties.getSsh().getUser(),
                    properties.getSsh().getKeyPath(),
                    properties.getSsh().getHelperPath(),
                    Map.of(spec.containerName(), properties.getTargetId()), specs);
            default -> throw new IllegalArgumentException("Unsupported Docker runbook transport: " + transport);
        };
    }

    private Duration positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return Duration.ofMillis(value);
    }

    private Duration nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return Duration.ofMillis(value);
    }
}
