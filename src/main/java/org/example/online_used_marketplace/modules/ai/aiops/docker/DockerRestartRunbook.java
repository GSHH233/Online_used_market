package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.util.Map;

/** Application-facing boundary for one configured, allowlisted Docker target. */
public interface DockerRestartRunbook {

    String targetId();

    DockerRestartResult execute(String targetId);

    Map<String, Object> catalogMetadata();
}
