package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class EvalDashboardVO {

    private Integer totalReports;

    private List<String> evalTypes = new ArrayList<>();

    private List<EvalReportSummaryVO> latestByType = new ArrayList<>();

    private List<EvalReportSummaryVO> recentReports = new ArrayList<>();

    private Map<String, Object> traceStats;
}
