package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.KnowledgeRepairBatchRequestDTO;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskType;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeIndexTaskVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeRepairBatchResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeReindexTriggerVO;

import java.util.List;

/**
 * @author Admin
 * @ClassName: KnowledgeIndexService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-25  23:55
 * @Version:
 */
public interface KnowledgeIndexService {
    KnowledgeReindexTriggerVO triggerReindex(Long docId);

    KnowledgeReindexTriggerVO triggerIngest(Long docId, Boolean async);

    KnowledgeReindexTriggerVO repairDoc(Long docId, Boolean forceReindex, Boolean async);

    /** Rebuild and activate a retained revision through the controlled-operation path. */
    KnowledgeReindexTriggerVO rollbackRevision(Long docId, Boolean async);

    KnowledgeRepairBatchResponseVO repairBatch(KnowledgeRepairBatchRequestDTO dto);

    KnowledgeIndexTaskVO getTask(Long taskId);

    List<KnowledgeIndexTaskVO> listTasks(Long docId,
                                         KnowledgeIndexTaskStatus status,
                                         KnowledgeIndexTaskType taskType,
                                         Integer limit);

    void executeQueuedTask(Long taskId, int retryCount);

    boolean markTaskQueued(Long taskId, int retryCount);

    void markTaskDeadLettered(Long taskId, int retryCount);
}
