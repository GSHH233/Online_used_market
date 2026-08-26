package org.example.online_used_marketplace.modules.ai.aiops.docker;

/** Structured result returned by a Docker command invocation. */
public record DockerCommandResult(
        int exitCode,
        boolean timedOut,
        String stdout,
        String stderr,
        String error,
        long elapsedMs) {

    public boolean succeeded() {
        return !timedOut && exitCode == 0 && (error == null || error.isBlank());
    }

    public static DockerCommandResult success(String stdout, long elapsedMs) {
        return new DockerCommandResult(0, false, stdout, "", "", elapsedMs);
    }

    public static DockerCommandResult failure(int exitCode, String stderr, long elapsedMs) {
        return new DockerCommandResult(exitCode, false, "", stderr, "", elapsedMs);
    }

    public static DockerCommandResult timeout(long elapsedMs) {
        return new DockerCommandResult(-1, true, "", "", "command timed out", elapsedMs);
    }
}
