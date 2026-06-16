package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.List;

@Data
public class StorageConsistencyReportVO {

    private StorageConsistencySummaryVO summary;

    private List<StorageConsistencyDocVO> docs;
}
