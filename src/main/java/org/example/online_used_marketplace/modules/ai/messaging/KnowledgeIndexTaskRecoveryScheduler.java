package org.example.online_used_marketplace.modules.ai.messaging;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionRabbitProperties;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeIndexTask;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStatus;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeIndexTaskMapper;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeIndexService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.knowledge.ingestion.rabbit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeIndexTaskRecoveryScheduler {

    private final KnowledgeIndexTaskMapper taskMapper;
    private final KnowledgeIndexService knowledgeIndexService;
    private final KnowledgeIndexTaskPublisher publisher;
    private final KnowledgeIngestionRabbitProperties properties;

    @Scheduled(
            initialDelayString = "${ai.knowledge.ingestion.rabbit.recovery-initial-delay-ms:15000}",
            fixedDelayString = "${ai.knowledge.ingestion.rabbit.recovery-fixed-delay-ms:30000}"
    )
    public void recoverUndeliveredTasks() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime runningStaleBefore = now.minusSeconds(Math.max(30L, properties.getRunningStaleSeconds()));
        int recoveredRunning = taskMapper.recoverStaleRunning(runningStaleBefore);

        LocalDateTime queuedStaleBefore = now.minusSeconds(Math.max(10L, properties.getQueuedStaleSeconds()));
        QueryWrapper<KnowledgeIndexTask> wrapper = new QueryWrapper<>();
        wrapper.eq("async_task", true)
                .and(status -> status.eq("status", KnowledgeIndexTaskStatus.PENDING.name())
                        .or(staleQueued -> staleQueued
                                .eq("status", KnowledgeIndexTaskStatus.QUEUED.name())
                                .lt("updated_time", queuedStaleBefore)))
                .orderByAsc("id")
                .last("limit " + Math.max(1, Math.min(properties.getRecoveryBatchSize(), 500)));

        List<KnowledgeIndexTask> recoverable = taskMapper.selectList(wrapper);
        int published = 0;
        for (KnowledgeIndexTask task : recoverable) {
            int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
            knowledgeIndexService.markTaskQueued(task.getId(), retryCount);
            if (publisher.publishInitial(new KnowledgeIndexTaskMessage(task.getId(), retryCount, task.getCreatedTime()))) {
                taskMapper.touchQueued(task.getId());
                published++;
            }
        }
        if (recoveredRunning > 0 || !recoverable.isEmpty()) {
            log.info("[KnowledgeIndexMQ] recovery scan finished, staleRunning={}, candidates={}, published={}",
                    recoveredRunning, recoverable.size(), published);
        }
    }
}
