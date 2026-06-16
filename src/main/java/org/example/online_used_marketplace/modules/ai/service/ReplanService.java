package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;

/**
 * @author Admin
 * @ClassName: ReplanService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-23  14:34
 * @Version:
 */
public interface ReplanService {
    ProductPublishAssistRequestDTO buildReplanRequest(ProductPublishAssistRequestDTO original, ProductPublishAssistVO firstResult);
    boolean shouldReplan(ProductPublishAssistVO firstResult);
    Boolean decideCanPublish(Integer riskScore);
}
