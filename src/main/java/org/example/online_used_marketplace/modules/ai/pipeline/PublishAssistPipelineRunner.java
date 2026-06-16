package org.example.online_used_marketplace.modules.ai.pipeline;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.context.PublishAssistResult;
import org.example.online_used_marketplace.modules.ai.context.PublishInputContext;
import org.example.online_used_marketplace.modules.ai.enums.AssistStepStatus;
import org.example.online_used_marketplace.modules.ai.vo.AssistStepTraceVO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 发布助手步骤执行器（统一执行、trace状态维护、异常收敛）
 */
@Component
@RequiredArgsConstructor
public class PublishAssistPipelineRunner {

    private final List<PublishAssistStep> steps;

    public void run(PublishInputContext context, PublishAssistResult result, List<AssistStepTraceVO> trace, Integer round) {
        for (PublishAssistStep step : steps) {
            String traceKey = step.stepKey() + "#" + round;
            markRunning(trace, traceKey);
            try {
                step.apply(context, result);
                markSuccess(trace, traceKey, step.stepLabel() + "完成");
            } catch (BusinessException e) {
                markFailed(trace, traceKey, step.stepLabel() + "失败");
                throw e;
            } catch (Exception e) {
                markFailed(trace, traceKey, step.stepLabel() + "失败");
                throw new BusinessException(ResultCode.INTERNAL_ERROR, e.getMessage());
            }
        }
    }
    private void markRunning(List<AssistStepTraceVO> vos, String step) {
        for (AssistStepTraceVO vo : vos) {
            if (vo.getStep().equals(step)) {
                vo.setStatus(AssistStepStatus.RUNNING);
                vo.setMessage("执行中");
                return;
            }
        }
    }
    private void markSuccess(List<AssistStepTraceVO> vos, String step, String msg) {
        for (AssistStepTraceVO vo : vos) {
            if (vo.getStep().equals(step)) {
                vo.setMessage(msg);
                vo.setStatus(AssistStepStatus.SUCCESS);
                return;
            }
        }
    }

    private void markFailed(List<AssistStepTraceVO> vos, String step, String msg) {
        for (AssistStepTraceVO vo : vos) {
            if (vo.getStep().equals(step)) {
                vo.setMessage(msg);
                vo.setStatus(AssistStepStatus.FAILED);
                return;
            }
        }
    }
}
