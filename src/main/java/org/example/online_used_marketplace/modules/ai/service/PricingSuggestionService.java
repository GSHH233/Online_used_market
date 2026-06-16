package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;

/**
 * @author Admin
 * @ClassName: PricingSuggestionService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-23  14:34
 * @Version:
 */
public interface PricingSuggestionService {
    String buildPriceSuggestion(ProductPublishAssistRequestDTO request, Integer riskScore);
}
