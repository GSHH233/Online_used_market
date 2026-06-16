package org.example.online_used_marketplace.modules.ai.agent.harness;

import lombok.Builder;
import lombok.Data;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class ToolExecutionResult {

    private String tool;

    private String status;

    private ToolQueryResultVO observation;

    private long elapsedMs;

    private int attempts;

    private int retryCount;

    private boolean timeout;

    private boolean fallbackUsed;

    private boolean snapshotRestored;

    private String errorMessage;

    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("harness", true);
        metadata.put("status", status);
        metadata.put("attempts", attempts);
        metadata.put("retryCount", retryCount);
        metadata.put("timeout", timeout);
        metadata.put("fallbackUsed", fallbackUsed);
        metadata.put("snapshotRestored", snapshotRestored);
        metadata.put("elapsedMs", elapsedMs);
        if (errorMessage != null && !errorMessage.isBlank()) {
            metadata.put("error", errorMessage);
        }
        return metadata;
    }
}
