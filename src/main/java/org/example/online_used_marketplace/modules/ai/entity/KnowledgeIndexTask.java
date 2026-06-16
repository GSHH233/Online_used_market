package org.example.online_used_marketplace.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStage;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskType;

import java.time.LocalDateTime;

/**
 * 知识索引任务
 */
@Data
@TableName("ai_knowledge_index_task")
public class KnowledgeIndexTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    /**
     * 任务类型：INGEST/REINDEX/REPAIR
     */
    private KnowledgeIndexTaskType taskType;

    /**
     * 状态：PENDING/QUEUED/RUNNING/SUCCESS/FAILED/DEAD_LETTERED
     */
    private KnowledgeIndexTaskStatus status;

    /**
     * 当前阶段：清理、切片、写 DB、写 ES、写 Milvus、校验等
     */
    private KnowledgeIndexTaskStage stage;

    private Boolean asyncTask;

    private Boolean forceRepair;

    private Integer chunkCount;

    private Integer dbCount;

    private Integer esCount;

    private Integer milvusCount;

    private Boolean consistent;

    private Integer retryCount;

    private Long elapsedMs;

    private String errorMsg;

    private String detailJson;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    @TableLogic
    private Integer isDeleted;
}
