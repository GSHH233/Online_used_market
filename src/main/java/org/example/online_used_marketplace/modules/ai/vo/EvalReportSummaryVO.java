package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class EvalReportSummaryVO {

    private String fileName;

    private String evalType;

    private Long sizeBytes;

    private LocalDateTime lastModifiedTime;

    private Map<String, String> metrics = new LinkedHashMap<>();

    private String preview;
}
