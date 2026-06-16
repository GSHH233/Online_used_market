package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.List;

@Data
public class KnowledgeRepairBatchResponseVO {

    private Integer checkedDocs;

    private Integer submittedTasks;

    private Integer skippedConsistentDocs;

    private List<StorageConsistencyDocVO> checkedDocsDetail;

    private List<StorageConsistencyDocVO> skippedDocs;

    private List<KnowledgeIndexTaskVO> tasks;
}
