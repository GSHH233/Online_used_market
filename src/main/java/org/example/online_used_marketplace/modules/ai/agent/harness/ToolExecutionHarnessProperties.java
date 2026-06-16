package org.example.online_used_marketplace.modules.ai.agent.harness;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "ai.agent.harness")
public class ToolExecutionHarnessProperties {

    private boolean enabled = true;

    private ToolExecutionPolicy defaults = new ToolExecutionPolicy();

    private Map<String, ToolExecutionPolicy> tools = new HashMap<>();

    public ToolExecutionPolicy policyFor(String tool) {
        ToolExecutionPolicy base = copy(defaults == null ? new ToolExecutionPolicy() : defaults);
        ToolExecutionPolicy override = tools == null ? null : tools.get(tool);
        if (override == null) {
            return base;
        }
        merge(base, override);
        return base;
    }

    private ToolExecutionPolicy copy(ToolExecutionPolicy src) {
        ToolExecutionPolicy out = new ToolExecutionPolicy();
        out.setEnabled(src.isEnabled());
        out.setTimeoutMs(src.getTimeoutMs());
        out.setMaxRetries(src.getMaxRetries());
        out.setRetryBackoffMs(src.getRetryBackoffMs());
        out.setSnapshotEnabled(src.isSnapshotEnabled());
        out.setSnapshotTtlSeconds(src.getSnapshotTtlSeconds());
        out.setFallbackSummary(src.getFallbackSummary());
        return out;
    }

    private void merge(ToolExecutionPolicy base, ToolExecutionPolicy override) {
        base.setEnabled(override.isEnabled());
        if (override.getTimeoutMs() > 0) {
            base.setTimeoutMs(override.getTimeoutMs());
        }
        if (override.getMaxRetries() >= 0) {
            base.setMaxRetries(override.getMaxRetries());
        }
        if (override.getRetryBackoffMs() >= 0) {
            base.setRetryBackoffMs(override.getRetryBackoffMs());
        }
        base.setSnapshotEnabled(override.isSnapshotEnabled());
        if (override.getSnapshotTtlSeconds() > 0) {
            base.setSnapshotTtlSeconds(override.getSnapshotTtlSeconds());
        }
        if (override.getFallbackSummary() != null && !override.getFallbackSummary().isBlank()) {
            base.setFallbackSummary(override.getFallbackSummary());
        }
    }
}
