package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class AgentTraceStepVO {

    private Integer index;

    private String phase;

    private String tool;

    private String status;

    private String thought;

    private String action;

    private String observation;

    private Long elapsedMs;

    private List<Long> sourceDocIds = new ArrayList<>();

    private Map<String, Object> metadata;
}
