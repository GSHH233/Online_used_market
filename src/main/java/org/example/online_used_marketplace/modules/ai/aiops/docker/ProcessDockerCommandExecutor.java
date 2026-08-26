package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Docker CLI adapter. ProcessBuilder receives an argument list directly, so
 * commands never pass through cmd.exe, PowerShell, sh, or a concatenated shell string.
 */
public final class ProcessDockerCommandExecutor implements DockerCommandExecutor {

    private static final int MAX_CAPTURE_BYTES = 32 * 1024;
    private final String dockerExecutable;

    public ProcessDockerCommandExecutor() {
        this("docker");
    }

    public ProcessDockerCommandExecutor(String dockerExecutable) {
        if (dockerExecutable == null || dockerExecutable.isBlank()
                || dockerExecutable.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("docker executable must be a non-blank path");
        }
        this.dockerExecutable = dockerExecutable;
    }

    @Override
    public DockerCommandResult execute(List<String> arguments, Duration timeout) {
        validateArguments(arguments);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        long startedAt = System.nanoTime();
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(dockerExecutable);
        command.addAll(arguments);
        Process process = null;
        ExecutorService streamReaders = Executors.newFixedThreadPool(2);
        try {
            Process startedProcess = new ProcessBuilder(command).start();
            process = startedProcess;
            Future<String> stdout = streamReaders.submit(() -> readCapped(startedProcess.getInputStream()));
            Future<String> stderr = streamReaders.submit(() -> readCapped(startedProcess.getErrorStream()));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return DockerCommandResult.timeout(elapsedMs(startedAt));
            }
            return new DockerCommandResult(
                    process.exitValue(),
                    false,
                    getCaptured(stdout),
                    getCaptured(stderr),
                    "",
                    elapsedMs(startedAt));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new DockerCommandResult(-1, false, "", "", "command interrupted", elapsedMs(startedAt));
        } catch (IOException ex) {
            return new DockerCommandResult(-1, false, "", "", ex.getMessage(), elapsedMs(startedAt));
        } finally {
            streamReaders.shutdownNow();
        }
    }

    private void validateArguments(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            throw new IllegalArgumentException("docker arguments must not be empty");
        }
        for (String argument : arguments) {
            if (argument == null || argument.isBlank() || argument.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("docker argument must be non-blank and NUL-free");
            }
        }
    }

    private String getCaptured(Future<String> captured) {
        try {
            return captured.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | java.util.concurrent.TimeoutException ex) {
            return "output capture failed: " + ex.getClass().getSimpleName();
        }
    }

    private String readCapped(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        int retained = 0;
        while ((read = input.read(buffer)) != -1) {
            int remaining = MAX_CAPTURE_BYTES - retained;
            if (remaining > 0) {
                int copyLength = Math.min(read, remaining);
                output.write(buffer, 0, copyLength);
                retained += copyLength;
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
