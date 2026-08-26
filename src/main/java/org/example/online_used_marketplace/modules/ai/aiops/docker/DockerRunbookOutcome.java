package org.example.online_used_marketplace.modules.ai.aiops.docker;

public enum DockerRunbookOutcome {
    DISABLED,
    REJECTED,
    HEALTHY_AFTER_RESTART,
    HEALTHY_AFTER_ROLLBACK,
    FAILED
}
