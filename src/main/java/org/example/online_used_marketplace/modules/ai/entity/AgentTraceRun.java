package org.example.online_used_marketplace.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_agent_trace_run")
public class AgentTraceRun {

    @TableId(type = IdType.AUTO)
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

    private LocalDateTime updatedTime;

    @TableLogic
    private Integer isDeleted;
}
