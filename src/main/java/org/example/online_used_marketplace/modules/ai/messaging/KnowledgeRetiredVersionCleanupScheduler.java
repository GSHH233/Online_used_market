package org.example.online_used_marketplace.modules.ai.messaging;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeRetiredVersionCleanupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs physical cleanup only after the retained revision's rollback window expires. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.knowledge.lifecycle", name = "cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeRetiredVersionCleanupScheduler {

    private final KnowledgeRetiredVersionCleanupService cleanupService;

    @Scheduled(
            initialDelayString = "${ai.knowledge.lifecycle.cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${ai.knowledge.lifecycle.cleanup-fixed-delay-ms:3600000}"
    )
    public void cleanupExpiredRetiredVersions() {
        cleanupService.cleanupExpiredRetiredVersions();
    }
}
