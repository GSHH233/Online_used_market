package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStage;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeIndexTaskType;

import java.time.LocalDateTime;

@Data
public class KnowledgeIndexTaskVO {

    private Long id;

    private Long docId;

    private KnowledgeIndexTaskType taskType;

    private KnowledgeIndexTaskStatus status;

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
}
