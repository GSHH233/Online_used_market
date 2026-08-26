package org.example.online_used_marketplace.modules.ai.aiops.docker;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerRestartRunbookExecutorTest {

    private static final String CONTAINER = "marketplace-demo-worker";
    private static final String IMAGE = "registry.example/marketplace-demo-worker:rollback-v1";

    @Test
    void defaultConstructorPolicyIsDisabledAndExecutesNothing() {
        RecordingDocker docker = new RecordingDocker(DockerCommandResult.success("", 1));
        DockerRestartRunbookExecutor executor = new DockerRestartRunbookExecutor(docker,
                (url, timeout) -> DockerHealthResult.healthy(200, 1));

        DockerRestartResult result = executor.execute(new DockerRestartRequest(CONTAINER, IMAGE));

        assertFalse(result.successful());
        assertEquals(DockerRunbookOutcome.DISABLED, result.outcome());
        assertTrue(docker.commands.isEmpty());
        assertEquals(DockerStageStatus.REJECTED, result.stages().get(0).status());
    }

    @Test
    void rejectsUnknownContainerAndMismatchedRollbackImageBeforeDockerCall() {
        RecordingDocker docker = new RecordingDocker(DockerCommandResult.success("", 1));
        DockerRestartRunbookExecutor executor = new DockerRestartRunbookExecutor(docker,
                (url, timeout) -> DockerHealthResult.healthy(200, 1), policy());

        DockerRestartResult unknown = executor.execute(new DockerRestartRequest("untrusted-container", IMAGE));
        DockerRestartResult mismatched = executor.execute(new DockerRestartRequest(CONTAINER, "evil/image:latest"));

        assertEquals(DockerRunbookOutcome.REJECTED, unknown.outcome());
        assertEquals(DockerRunbookOutcome.REJECTED, mismatched.outcome());
        assertTrue(docker.commands.isEmpty());
    }

    @Test
    void restartsAndReturnsHealthyAfterHttpCheck() {
        RecordingDocker docker = new RecordingDocker(DockerCommandResult.success("restarted", 8));
        RecordingHealth health = new RecordingHealth(DockerHealthResult.healthy(200, 3));
        DockerRestartRunbookExecutor executor = new DockerRestartRunbookExecutor(docker, health, policy());

        DockerRestartResult result = executor.execute(request());

        assertTrue(result.successful());
        assertEquals(DockerRunbookOutcome.HEALTHY_AFTER_RESTART, result.outcome());
        assertFalse(result.rollbackAttempted());
        assertEquals(List.of("restart", "--time", "5", CONTAINER), docker.commands.get(0));
        assertEquals(1, health.calls);
    }

    @Test
    void failedRestartHealthRecreatesWithTrustedImageAndChecksRollbackHealth() {
        RecordingDocker docker = new RecordingDocker(
                DockerCommandResult.success("restarted", 8),
                DockerCommandResult.success("removed", 4),
                DockerCommandResult.success("container-id", 9));
        RecordingHealth health = new RecordingHealth(
                DockerHealthResult.unhealthy(503, "unhealthy", 2),
                DockerHealthResult.healthy(200, 2));
        DockerRestartRunbookExecutor executor = new DockerRestartRunbookExecutor(docker, health, policy());

        DockerRestartResult result = executor.execute(request());

        assertTrue(result.successful());
        assertTrue(result.rollbackAttempted());
        assertTrue(result.rollbackSuccessful());
        assertEquals(DockerRunbookOutcome.HEALTHY_AFTER_ROLLBACK, result.outcome());
        assertEquals(List.of("rm", "--force", CONTAINER), docker.commands.get(1));
        assertEquals(List.of("run", "--detach", "--name", CONTAINER,
                "--restart", "unless-stopped", "--publish", "18080:8080", IMAGE), docker.commands.get(2));
        assertEquals(DockerStageStatus.FAILED, stage(result, DockerStage.HEALTH_CHECK).status());
        assertEquals(DockerStageStatus.SUCCESS, stage(result, DockerStage.ROLLBACK_HEALTH_CHECK).status());
    }

    @Test
    void rollbackFailureIsReportedAsStructuredFailure() {
        RecordingDocker docker = new RecordingDocker(
                DockerCommandResult.success("restarted", 8),
                DockerCommandResult.failure(1, "permission denied", 4));
        RecordingHealth health = new RecordingHealth(DockerHealthResult.unhealthy(503, "unhealthy", 2));
        DockerRestartRunbookExecutor executor = new DockerRestartRunbookExecutor(docker, health, policy());

        DockerRestartResult result = executor.execute(request());

        assertFalse(result.successful());
        assertTrue(result.rollbackAttempted());
        assertFalse(result.rollbackSuccessful());
        assertEquals(DockerRunbookOutcome.FAILED, result.outcome());
        assertEquals(DockerStageStatus.FAILED, stage(result, DockerStage.ROLLBACK_REMOVE).status());
        assertEquals(DockerStageStatus.SKIPPED, stage(result, DockerStage.ROLLBACK_RECREATE).status());
        assertEquals(2, docker.commands.size());
    }

    @Test
    void rollbackHealthFailureIsNotReportedAsSuccessfulRecovery() {
        RecordingDocker docker = new RecordingDocker(
                DockerCommandResult.success("restarted", 8),
                DockerCommandResult.success("removed", 4),
                DockerCommandResult.success("container-id", 9));
        RecordingHealth health = new RecordingHealth(
                DockerHealthResult.unhealthy(503, "unhealthy", 2),
                DockerHealthResult.unhealthy(503, "still unhealthy", 2));
        DockerRestartRunbookExecutor executor = new DockerRestartRunbookExecutor(docker, health, policy());

        DockerRestartResult result = executor.execute(request());

        assertFalse(result.successful());
        assertTrue(result.rollbackAttempted());
        assertFalse(result.rollbackSuccessful());
        assertEquals(DockerRunbookOutcome.FAILED, result.outcome());
        assertEquals(DockerStageStatus.FAILED, stage(result, DockerStage.ROLLBACK_HEALTH_CHECK).status());
    }

    @Test
    void commandTimeoutStopsTheFlowAndDoesNotPretendRestartWasReversible() {
        RecordingDocker docker = new RecordingDocker(DockerCommandResult.timeout(30));
        DockerRestartRunbookExecutor executor = new DockerRestartRunbookExecutor(docker,
                (url, timeout) -> DockerHealthResult.healthy(200, 1), policy());

        DockerRestartResult result = executor.execute(request());

        assertFalse(result.successful());
        assertEquals(DockerRunbookOutcome.FAILED, result.outcome());
        assertEquals(DockerStageStatus.FAILED, stage(result, DockerStage.RESTART).status());
        assertEquals(DockerStageStatus.SKIPPED, stage(result, DockerStage.HEALTH_CHECK).status());
        assertFalse(result.rollbackAttempted());
    }

    private DockerRestartRequest request() {
        return new DockerRestartRequest(CONTAINER, IMAGE);
    }

    private DockerRestartPolicy policy() {
        DockerServiceSpec spec = new DockerServiceSpec(CONTAINER, IMAGE,
                URI.create("http://127.0.0.1:18080/actuator/health"), 18080, 8080, 5);
        return new DockerRestartPolicy(true, Map.of(CONTAINER, spec),
                Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ZERO, 1);
    }

    private DockerStageResult stage(DockerRestartResult result, DockerStage target) {
        return result.stages().stream().filter(item -> item.stage() == target).findFirst().orElseThrow();
    }

    private static final class RecordingDocker implements DockerCommandExecutor {
        private final Deque<DockerCommandResult> results = new ArrayDeque<>();
        private final List<List<String>> commands = new ArrayList<>();

        private RecordingDocker(DockerCommandResult... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public DockerCommandResult execute(List<String> arguments, Duration timeout) {
            commands.add(List.copyOf(arguments));
            return results.isEmpty() ? DockerCommandResult.success("", 1) : results.removeFirst();
        }
    }

    private static final class RecordingHealth implements DockerHealthChecker {
        private final Deque<DockerHealthResult> results = new ArrayDeque<>();
        private int calls;

        private RecordingHealth(DockerHealthResult... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public DockerHealthResult check(URI healthUrl, Duration timeout) {
            calls++;
            return results.isEmpty() ? DockerHealthResult.unhealthy(503, "no scripted result", 1)
                    : results.removeFirst();
        }
    }
}
