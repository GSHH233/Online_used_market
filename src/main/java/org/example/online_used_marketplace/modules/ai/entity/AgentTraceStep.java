package org.example.online_used_marketplace.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_agent_trace_step")
public class AgentTraceStep {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String traceId;

    private Integer stepIndex;

    private String phase;

    private String tool;

    private String status;

    private String thought;

    private String action;

    private String observation;

    private Long elapsedMs;

    private String sourceDocIdsJson;

    private String metadataJson;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    @TableLogic
    private Integer isDeleted;
}
