package org.example.online_used_marketplace.modules.ai.aiops.docker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Hides infrastructure identifiers from the API. Callers provide only a
 * logical target id; container/image/port values come from trusted config.
 */
public final class IsolatedDockerRestartRunbook implements DockerRestartRunbook {

    private final String targetId;
    private final DockerServiceSpec serviceSpec;
    private final DockerRestartRunbookExecutor executor;
    private final String transport;

    public IsolatedDockerRestartRunbook(String targetId,
                                        DockerServiceSpec serviceSpec,
                                        DockerRestartRunbookExecutor executor,
                                        String transport) {
        if (targetId == null || !targetId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("targetId is not allowlist-safe");
        }
        this.targetId = targetId;
        this.serviceSpec = Objects.requireNonNull(serviceSpec, "serviceSpec");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transport = transport == null ? "" : transport;
    }

    @Override
    public String targetId() {
        return targetId;
    }

    @Override
    public DockerRestartResult execute(String requestedTargetId) {
        if (!targetId.equals(requestedTargetId)) {
            return new DockerRestartResult(false, false, false, DockerRunbookOutcome.REJECTED,
                    "Docker target is not allowlisted", java.util.List.of(
                    new DockerStageResult(DockerStage.POLICY, DockerStageStatus.REJECTED,
                            "logical target did not match configured allowlist", 0,
                            Map.of("targetId", requestedTargetId == null ? "" : requestedTargetId))));
        }
        return executor.execute(new DockerRestartRequest(
                serviceSpec.containerName(), serviceSpec.rollbackImage()));
    }

    @Override
    public Map<String, Object> catalogMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("enabled", true);
        metadata.put("targetId", targetId);
        metadata.put("transport", transport);
        metadata.put("healthUrl", serviceSpec.healthUrl().toString());
        metadata.put("rollbackImagePinned", true);
        metadata.put("containerNameExposedToApi", false);
        return metadata;
    }
}
