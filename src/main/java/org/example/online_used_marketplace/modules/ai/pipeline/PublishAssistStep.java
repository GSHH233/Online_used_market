package org.example.online_used_marketplace.modules.ai.pipeline;

import org.example.online_used_marketplace.modules.ai.context.PublishAssistResult;
import org.example.online_used_marketplace.modules.ai.context.PublishInputContext;

/**
 * @author Admin
 * @ClassName: PublishAssistStep
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-25  20:42
 * @Version:
 */
public interface PublishAssistStep {
    void apply(PublishInputContext context, PublishAssistResult result);
    String stepKey();
    String stepLabel();
}
