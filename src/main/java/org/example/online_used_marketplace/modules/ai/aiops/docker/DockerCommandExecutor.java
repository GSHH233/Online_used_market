package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.time.Duration;
import java.util.List;

/** Executes Docker CLI arguments without invoking a shell. */
public interface DockerCommandExecutor {

    DockerCommandResult execute(List<String> arguments, Duration timeout);
}
