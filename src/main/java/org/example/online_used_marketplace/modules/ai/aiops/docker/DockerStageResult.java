package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.util.Map;

/** One auditable stage of the runbook execution. */
public record DockerStageResult(
        DockerStage stage,
        DockerStageStatus status,
        String message,
        long elapsedMs,
        Map<String, Object> details) {

    public DockerStageResult {
        details = details == null ? Map.of() : Map.copyOf(details);
        message = message == null ? "" : message;
    }
}
