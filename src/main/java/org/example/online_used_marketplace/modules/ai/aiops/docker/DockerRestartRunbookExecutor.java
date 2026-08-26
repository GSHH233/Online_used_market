package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Isolated runbook core:
 * restart -> HTTP health polling -> rollback image recreation when health fails.
 *
 * This class has no Spring bean annotation and is disabled unless an explicit
 * enabled policy with allowlisted services is supplied by the caller.
 */
public final class DockerRestartRunbookExecutor {

    private final DockerCommandExecutor commandExecutor;
    private final DockerHealthChecker healthChecker;
    private final DockerRestartPolicy policy;

    public DockerRestartRunbookExecutor(DockerCommandExecutor commandExecutor,
                                        DockerHealthChecker healthChecker) {
        this(commandExecutor, healthChecker, DockerRestartPolicy.disabled());
    }

    public DockerRestartRunbookExecutor(DockerCommandExecutor commandExecutor,
                                        DockerHealthChecker healthChecker,
                                        DockerRestartPolicy policy) {
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
        this.healthChecker = Objects.requireNonNull(healthChecker, "healthChecker");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public DockerRestartResult execute(DockerRestartRequest request) {
        List<DockerStageResult> stages = new ArrayList<>();
        if (!policy.enabled()) {
            stages.add(stage(DockerStage.POLICY, DockerStageStatus.REJECTED,
                    "Docker restart runbook is disabled by policy", 0,
                    Map.of("enabled", false)));
            return result(false, false, false, DockerRunbookOutcome.DISABLED,
                    "Docker restart runbook is disabled", stages);
        }
        if (request == null) {
            stages.add(stage(DockerStage.POLICY, DockerStageStatus.REJECTED,
                    "request is required", 0, Map.of()));
            return result(false, false, false, DockerRunbookOutcome.REJECTED,
                    "Docker restart request is required", stages);
        }

        DockerServiceSpec spec = policy.service(request.containerName());
        if (spec == null) {
            stages.add(stage(DockerStage.POLICY, DockerStageStatus.REJECTED,
                    "container is not allowlisted", 0,
                    Map.of("containerName", request.containerName())));
            return result(false, false, false, DockerRunbookOutcome.REJECTED,
                    "container is not allowlisted", stages);
        }
        if (!spec.rollbackImage().equals(request.rollbackImage())) {
            stages.add(stage(DockerStage.POLICY, DockerStageStatus.REJECTED,
                    "rollback image is not allowlisted for container", 0,
                    Map.of("containerName", spec.containerName(), "rollbackImage", request.rollbackImage())));
            return result(false, false, false, DockerRunbookOutcome.REJECTED,
                    "rollback image is not allowlisted", stages);
        }
        stages.add(stage(DockerStage.POLICY, DockerStageStatus.SUCCESS,
                "container and rollback image passed allowlist validation", 0,
                Map.of("containerName", spec.containerName(), "rollbackImage", spec.rollbackImage())));

        DockerCommandResult restart = commandExecutor.execute(
                List.of("restart", "--time", Integer.toString(spec.restartGraceSeconds()), spec.containerName()),
                policy.commandTimeout());
        stages.add(commandStage(DockerStage.RESTART, restart));
        if (!restart.succeeded()) {
            stages.add(skipped(DockerStage.HEALTH_CHECK, "restart command failed; health check not attempted"));
            return result(false, false, false, DockerRunbookOutcome.FAILED,
                    "Docker restart failed; no rollback was attempted", stages);
        }

        HealthObservation afterRestart = awaitHealthy(spec, policy.healthTimeout(), policy.healthPollInterval(),
                policy.maxHealthAttempts());
        stages.add(healthStage(DockerStage.HEALTH_CHECK, afterRestart));
        if (afterRestart.healthy()) {
            stages.add(stage(DockerStage.COMPLETE, DockerStageStatus.SUCCESS,
                    "container is healthy after restart", afterRestart.elapsedMs(),
                    Map.of("outcome", DockerRunbookOutcome.HEALTHY_AFTER_RESTART.name())));
            return result(true, false, false, DockerRunbookOutcome.HEALTHY_AFTER_RESTART,
                    "Docker restart completed and health check passed", stages);
        }

        DockerCommandResult remove = commandExecutor.execute(
                List.of("rm", "--force", spec.containerName()), policy.commandTimeout());
        stages.add(commandStage(DockerStage.ROLLBACK_REMOVE, remove));
        if (!remove.succeeded()) {
            stages.add(skipped(DockerStage.ROLLBACK_RECREATE,
                    "rollback recreate skipped because old container could not be removed"));
            stages.add(skipped(DockerStage.ROLLBACK_HEALTH_CHECK,
                    "rollback health check skipped because rollback recreate did not run"));
            return result(false, true, false, DockerRunbookOutcome.FAILED,
                    "health check failed and rollback container removal failed", stages);
        }

        List<String> recreateArguments = List.of(
                "run", "--detach",
                "--name", spec.containerName(),
                "--restart", "unless-stopped",
                "--publish", spec.hostPort() + ":" + spec.containerPort(),
                spec.rollbackImage());
        DockerCommandResult recreate = commandExecutor.execute(recreateArguments, policy.commandTimeout());
        stages.add(commandStage(DockerStage.ROLLBACK_RECREATE, recreate));
        if (!recreate.succeeded()) {
            stages.add(skipped(DockerStage.ROLLBACK_HEALTH_CHECK,
                    "rollback health check skipped because trusted image recreation failed"));
            return result(false, true, false, DockerRunbookOutcome.FAILED,
                    "health check failed and rollback image recreation failed", stages);
        }

        HealthObservation afterRollback = awaitHealthy(spec, policy.healthTimeout(), policy.healthPollInterval(),
                policy.maxHealthAttempts());
        stages.add(healthStage(DockerStage.ROLLBACK_HEALTH_CHECK, afterRollback));
        if (afterRollback.healthy()) {
            stages.add(stage(DockerStage.COMPLETE, DockerStageStatus.SUCCESS,
                    "trusted rollback image is healthy; restart itself was not undone", afterRollback.elapsedMs(),
                    Map.of("outcome", DockerRunbookOutcome.HEALTHY_AFTER_ROLLBACK.name(),
                            "rollbackImage", spec.rollbackImage())));
            return result(true, true, true, DockerRunbookOutcome.HEALTHY_AFTER_ROLLBACK,
                    "restart health check failed; trusted rollback image restored service health", stages);
        }
        stages.add(stage(DockerStage.COMPLETE, DockerStageStatus.FAILED,
                "both restart and rollback health checks failed", afterRollback.elapsedMs(),
                Map.of("outcome", DockerRunbookOutcome.FAILED.name())));
        return result(false, true, false, DockerRunbookOutcome.FAILED,
                "restart and rollback health checks failed; manual intervention required", stages);
    }

    private HealthObservation awaitHealthy(DockerServiceSpec spec,
                                           Duration timeout,
                                           Duration pollInterval,
                                           int maxAttempts) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        long startedAt = System.nanoTime();
        DockerHealthResult last = DockerHealthResult.unhealthy(0, "health check not attempted", 0);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            last = healthChecker.check(spec.healthUrl(), timeout);
            attempts.add(Map.of(
                    "attempt", attempt,
                    "healthy", last.healthy(),
                    "statusCode", last.statusCode(),
                    "error", last.error() == null ? "" : last.error(),
                    "elapsedMs", last.elapsedMs()));
            if (last.healthy()) {
                return new HealthObservation(true, attempt, last, elapsedMs(startedAt), attempts);
            }
            if (attempt < maxAttempts && !pollInterval.isZero()) {
                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    last = DockerHealthResult.unhealthy(0, "health polling interrupted", last.elapsedMs());
                    break;
                }
            }
        }
        return new HealthObservation(false, attempts.size(), last, elapsedMs(startedAt), attempts);
    }

    private DockerStageResult commandStage(DockerStage stage, DockerCommandResult command) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exitCode", command.exitCode());
        details.put("timedOut", command.timedOut());
        details.put("stdout", safeOutput(command.stdout()));
        details.put("stderr", safeOutput(command.stderr()));
        if (command.error() != null && !command.error().isBlank()) {
            details.put("error", safeOutput(command.error()));
        }
        return stage(stage,
                command.succeeded() ? DockerStageStatus.SUCCESS : DockerStageStatus.FAILED,
                command.succeeded() ? "docker command completed" : "docker command failed",
                command.elapsedMs(), details);
    }

    private DockerStageResult healthStage(DockerStage stage, HealthObservation observation) {
        DockerHealthResult health = observation.lastResult();
        return stage(stage,
                observation.healthy() ? DockerStageStatus.SUCCESS : DockerStageStatus.FAILED,
                observation.healthy() ? "HTTP health check passed" : "HTTP health check failed",
                observation.elapsedMs(),
                Map.of("attempts", observation.attempts(),
                        "statusCode", health.statusCode(),
                        "error", health.error() == null ? "" : health.error(),
                        "checks", observation.checks()));
    }

    private DockerStageResult skipped(DockerStage stage, String message) {
        return stage(stage, DockerStageStatus.SKIPPED, message, 0, Map.of());
    }

    private DockerStageResult stage(DockerStage stage,
                                    DockerStageStatus status,
                                    String message,
                                    long elapsedMs,
                                    Map<String, Object> details) {
        return new DockerStageResult(stage, status, message, elapsedMs, details);
    }

    private DockerRestartResult result(boolean successful,
                                       boolean rollbackAttempted,
                                       boolean rollbackSuccessful,
                                       DockerRunbookOutcome outcome,
                                       String message,
                                       List<DockerStageResult> stages) {
        return new DockerRestartResult(successful, rollbackAttempted, rollbackSuccessful,
                outcome, message, stages);
    }

    private String safeOutput(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 2048 ? value : value.substring(0, 2048) + "...";
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private record HealthObservation(boolean healthy,
                                     int attempts,
                                     DockerHealthResult lastResult,
                                     long elapsedMs,
                                     List<Map<String, Object>> checks) {
    }
}
