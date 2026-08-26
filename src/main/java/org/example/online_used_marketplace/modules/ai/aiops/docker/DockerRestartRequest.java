package org.example.online_used_marketplace.modules.ai.aiops.docker;

/** Request for one allowlisted Docker service restart. */
public record DockerRestartRequest(String containerName, String rollbackImage) {

    public DockerRestartRequest {
        if (containerName == null || containerName.isBlank()) {
            throw new IllegalArgumentException("containerName must not be blank");
        }
        if (rollbackImage == null || rollbackImage.isBlank()) {
            throw new IllegalArgumentException("rollbackImage must not be blank");
        }
    }
}
