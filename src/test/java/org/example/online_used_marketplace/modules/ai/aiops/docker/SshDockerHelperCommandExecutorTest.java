package org.example.online_used_marketplace.modules.ai.aiops.docker;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SshDockerHelperCommandExecutorTest {

    private static final DockerServiceSpec SPEC = new DockerServiceSpec(
            "used-market-runbook-demo", "nginx:1.27-alpine",
            URI.create("http://192.168.152.128:18080/"), 18080, 80, 10);

    @Test
    void mapsOnlyFixedRestartShapeToRemoteHelper() {
        RecordingProcess process = new RecordingProcess();
        SshDockerHelperCommandExecutor executor = executor(process);

        executor.execute(List.of("restart", "--time", "10", SPEC.containerName()),
                Duration.ofSeconds(5));

        assertEquals(List.of(
                "-o", "BatchMode=yes",
                "-o", "StrictHostKeyChecking=yes",
                "-o", "LogLevel=ERROR",
                "-p", "22",
                "gshhh@192.168.152.128",
                "sudo", "/usr/local/sbin/used-market-docker-runbook",
                "restart", "marketplace-demo-web"), process.arguments);
    }

    @Test
    void recreateImageAndPortsMustExactlyMatchTrustedSpec() {
        RecordingProcess process = new RecordingProcess();
        SshDockerHelperCommandExecutor executor = executor(process);
        List<String> trusted = List.of("run", "--detach", "--name", SPEC.containerName(),
                "--restart", "unless-stopped", "--publish", "18080:80", "nginx:1.27-alpine");

        executor.execute(trusted, Duration.ofSeconds(5));

        assertEquals("recreate", process.arguments.get(process.arguments.size() - 2));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(
                List.of("run", "--detach", "--name", SPEC.containerName(),
                        "--restart", "unless-stopped", "--publish", "18080:80", "evil:latest"),
                Duration.ofSeconds(5)));
    }

    @Test
    void rejectsUnknownDockerCommandBeforeStartingSsh() {
        RecordingProcess process = new RecordingProcess();
        SshDockerHelperCommandExecutor executor = executor(process);

        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(List.of("exec", SPEC.containerName(), "sh"), Duration.ofSeconds(5)));
        assertEquals(List.of(), process.arguments);
    }

    private SshDockerHelperCommandExecutor executor(RecordingProcess process) {
        return new SshDockerHelperCommandExecutor(process,
                "192.168.152.128", 22, "gshhh", "",
                "/usr/local/sbin/used-market-docker-runbook",
                Map.of(SPEC.containerName(), "marketplace-demo-web"),
                Map.of(SPEC.containerName(), SPEC));
    }

    private static final class RecordingProcess implements DockerCommandExecutor {
        private List<String> arguments = new ArrayList<>();

        @Override
        public DockerCommandResult execute(List<String> arguments, Duration timeout) {
            this.arguments = List.copyOf(arguments);
            return DockerCommandResult.success("ok", 1);
        }
    }
}
