package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps the executor's fixed Docker argument shapes to a root-owned remote
 * helper. No API value becomes a remote command fragment.
 */
public final class SshDockerHelperCommandExecutor implements DockerCommandExecutor {

    private final DockerCommandExecutor processExecutor;
    private final String destination;
    private final int port;
    private final String keyPath;
    private final String helperPath;
    private final Map<String, String> targetsByContainer;
    private final Map<String, DockerServiceSpec> specsByContainer;

    public SshDockerHelperCommandExecutor(String sshExecutable,
                                          String host,
                                          int port,
                                          String user,
                                          String keyPath,
                                          String helperPath,
                                          Map<String, String> targetsByContainer,
                                          Map<String, DockerServiceSpec> specsByContainer) {
        this(new ProcessDockerCommandExecutor(sshExecutable), host, port, user, keyPath,
                helperPath, targetsByContainer, specsByContainer);
    }

    SshDockerHelperCommandExecutor(DockerCommandExecutor processExecutor,
                                   String host,
                                   int port,
                                   String user,
                                   String keyPath,
                                   String helperPath,
                                   Map<String, String> targetsByContainer,
                                   Map<String, DockerServiceSpec> specsByContainer) {
        this.processExecutor = Objects.requireNonNull(processExecutor, "processExecutor");
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) {
            throw new IllegalArgumentException("ssh host is invalid");
        }
        if (user == null || !user.matches("[A-Za-z_][A-Za-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("ssh user is invalid");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("ssh port is invalid");
        }
        if (helperPath == null || !helperPath.matches("/[A-Za-z0-9_./-]{1,240}")) {
            throw new IllegalArgumentException("helperPath must be an absolute safe path");
        }
        this.destination = user + "@" + host;
        this.port = port;
        this.keyPath = keyPath == null ? "" : keyPath.trim();
        this.helperPath = helperPath;
        this.targetsByContainer = Map.copyOf(targetsByContainer);
        this.specsByContainer = Map.copyOf(specsByContainer);
    }

    @Override
    public DockerCommandResult execute(List<String> arguments, Duration timeout) {
        RemoteAction action = translate(arguments);
        List<String> sshArguments = new ArrayList<>();
        sshArguments.addAll(List.of(
                "-o", "BatchMode=yes",
                "-o", "StrictHostKeyChecking=yes",
                "-o", "LogLevel=ERROR",
                "-p", Integer.toString(port)));
        if (!keyPath.isBlank()) {
            sshArguments.add("-i");
            sshArguments.add(keyPath);
        }
        sshArguments.add(destination);
        sshArguments.add("sudo");
        sshArguments.add(helperPath);
        sshArguments.add(action.command());
        sshArguments.add(action.targetId());
        return processExecutor.execute(sshArguments, timeout);
    }

    private RemoteAction translate(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            throw new IllegalArgumentException("docker command is empty");
        }
        if (arguments.size() == 4 && "restart".equals(arguments.get(0))
                && "--time".equals(arguments.get(1)) && arguments.get(2).matches("\\d{1,3}")) {
            return action("restart", arguments.get(3));
        }
        if (arguments.size() == 3 && "rm".equals(arguments.get(0))
                && "--force".equals(arguments.get(1))) {
            return action("remove", arguments.get(2));
        }
        if (arguments.size() == 9 && "run".equals(arguments.get(0))) {
            String container = arguments.get(3);
            DockerServiceSpec spec = requireSpec(container);
            List<String> expected = List.of("run", "--detach", "--name", container,
                    "--restart", "unless-stopped", "--publish",
                    spec.hostPort() + ":" + spec.containerPort(), spec.rollbackImage());
            if (!expected.equals(arguments)) {
                throw new IllegalArgumentException("docker recreate command does not match allowlisted spec");
            }
            return action("recreate", container);
        }
        throw new IllegalArgumentException("docker command shape is not allowlisted");
    }

    private RemoteAction action(String command, String container) {
        requireSpec(container);
        String target = targetsByContainer.get(container);
        if (target == null || !target.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("container has no safe logical target mapping");
        }
        return new RemoteAction(command, target);
    }

    private DockerServiceSpec requireSpec(String container) {
        DockerServiceSpec spec = specsByContainer.get(container);
        if (spec == null || !targetsByContainer.containsKey(container)) {
            throw new IllegalArgumentException("container is not allowlisted for SSH helper");
        }
        return spec;
    }

    private record RemoteAction(String command, String targetId) { }
}
