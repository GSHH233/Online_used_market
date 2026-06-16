package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;
import org.example.online_used_marketplace.modules.ai.enums.AssistStepStatus;

/**
 * @author Admin
 * @ClassName: AssistStepTraceVO
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-22  9:31
 * @Version:
 */
@Data
public class AssistStepTraceVO {
    private String step;
    private String label;
    private AssistStepStatus status;
    private String message;
}
