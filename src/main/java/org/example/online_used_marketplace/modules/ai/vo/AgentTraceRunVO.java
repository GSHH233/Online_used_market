package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AgentTraceRunVO {

    private Long id;

    private String traceId;

    private String sessionId;

    private String agentType;

    private String mode;

    private String question;

    private String answer;

    private String status;

    private Boolean success;

    private Long elapsedMs;

    private Integer stepCount;

    private Integer observationCount;

    private Integer sourceCount;

    private String tools;

    private String debugInfo;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdTime;

    private List<AgentTraceStepPersistVO> steps = new ArrayList<>();
}
