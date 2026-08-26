package org.example.online_used_marketplace.modules.ai.aiops.docker;

public enum DockerStage {
    POLICY,
    RESTART,
    HEALTH_CHECK,
    ROLLBACK_REMOVE,
    ROLLBACK_RECREATE,
    ROLLBACK_HEALTH_CHECK,
    COMPLETE
}
