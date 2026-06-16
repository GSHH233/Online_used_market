package org.example.online_used_marketplace.modules.ai.dto;

import lombok.Data;

@Data
public class KnowledgeRepairBatchRequestDTO {

    private Integer sampleDocs = 20;

    private Integer perDocLimit = 200;

    private Boolean forceReindex = false;

    private Boolean async = false;
}
