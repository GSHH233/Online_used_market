package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.List;

@Data
public class StorageConsistencySummaryVO {

    private Integer checkedDocs;

    private Integer consistentDocs;

    private Integer inconsistentDocs;

    private Double passRate;

    private List<String> storage;
}
