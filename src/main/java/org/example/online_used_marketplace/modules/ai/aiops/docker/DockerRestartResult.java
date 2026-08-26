package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.util.List;

/** Complete structured result; rollback success never disguises restart health failure. */
public record DockerRestartResult(
        boolean successful,
        boolean rollbackAttempted,
        boolean rollbackSuccessful,
        DockerRunbookOutcome outcome,
        String message,
        List<DockerStageResult> stages) {

    public DockerRestartResult {
        stages = stages == null ? List.of() : List.copyOf(stages);
        message = message == null ? "" : message;
    }
}
