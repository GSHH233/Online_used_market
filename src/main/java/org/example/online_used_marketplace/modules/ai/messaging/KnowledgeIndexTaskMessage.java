package org.example.online_used_marketplace.modules.ai.messaging;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexTaskMessage implements Serializable {

    private Long taskId;

    private int retryCount;

    private LocalDateTime createdAt;

    public static KnowledgeIndexTaskMessage initial(Long taskId) {
        return new KnowledgeIndexTaskMessage(taskId, 0, LocalDateTime.now());
    }

    public KnowledgeIndexTaskMessage nextRetry() {
        return new KnowledgeIndexTaskMessage(taskId, retryCount + 1, createdAt);
    }
}
