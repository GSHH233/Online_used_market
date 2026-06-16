package org.example.online_used_marketplace.modules.ai.pipeline.pipelineImpl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.context.PublishAssistResult;
import org.example.online_used_marketplace.modules.ai.context.PublishInputContext;
import org.example.online_used_marketplace.modules.ai.pipeline.PublishAssistStep;
import org.example.online_used_marketplace.modules.ai.service.ReplanService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * 发布决策步骤
 */
@Service
@Order(4)
@RequiredArgsConstructor
public class DecisionStepImpl implements PublishAssistStep {
    private final ReplanService replanService;

    @Override
    public void apply(PublishInputContext context, PublishAssistResult result) {
        Boolean canPublish = replanService.decideCanPublish(result.getRiskScore());
        result.setCanPublish(canPublish);
    }

    @Override
    public String stepKey() {
        return "decision";
    }

    @Override
    public String stepLabel() {
        return "发布决策";
    }
}
