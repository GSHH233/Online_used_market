package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChunkQualityReportVO {
    private int chunkCount;
    private int emptyCount;
    private int shortCount;
    private int oversizeCount;
    private int overlapCount;
    private int tokenP50;
    private int tokenP95;
    private int tokenMax;
    private double headingCoverage;
    private double pageCoverage;
    private double sourcePositionCoverage;
    private double sourceTextCoverage;
    private double abnormalDuplicateRate;
    private boolean valid;
    private List<String> violations = new ArrayList<>();
}
