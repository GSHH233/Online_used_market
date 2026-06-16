package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.PricingSuggestionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * @author Admin
 * @ClassName: PricingSuggestionService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-23  14:36
 * @Version:
 */
@Service
@RequiredArgsConstructor
public class PricingSuggestionServiceImpl implements PricingSuggestionService {
    public String buildPriceSuggestion(ProductPublishAssistRequestDTO request, Integer riskScore) {
        BigDecimal expectedPrice = request.getExpectedPrice();

        if (expectedPrice == null) {
            return "未提供期望价格，建议参考同类近7日成交均价后上架";
        }

        if (expectedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return "期望价格异常，请填写大于0的价格";
        }

        if (riskScore < 60) {
            return "当前文案风险较高，建议先修正文案后再定价发布";
        }

        if (expectedPrice.compareTo(new BigDecimal("100")) < 0) {
            return "建议设置包邮或同城自提，提升低价商品成交率";
        }

        return "建议先按期望价格发布，若48小时曝光较低可下调5%-10%";
    }
}
