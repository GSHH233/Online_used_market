package org.example.online_used_marketplace.modules.ai.agent.harness;

import lombok.Data;

@Data
public class ToolExecutionPolicy {

    private boolean enabled = true;

    /** 单次工具调用超时时间。 */
    private long timeoutMs = 3000L;

    /** 失败后的重试次数，不包含第一次调用。 */
    private int maxRetries = 1;

    /** 线性退避基准时间。第 n 次重试等待 retryBackoffMs * n。 */
    private long retryBackoffMs = 120L;

    /** 是否在成功调用后写入快照，并在失败时尝试恢复。 */
    private boolean snapshotEnabled = true;

    /** 快照有效期。 */
    private long snapshotTtlSeconds = 600L;

    /** 所有重试失败且无快照时的降级摘要。 */
    private String fallbackSummary = "工具暂时不可用，已返回降级 Observation";
}
