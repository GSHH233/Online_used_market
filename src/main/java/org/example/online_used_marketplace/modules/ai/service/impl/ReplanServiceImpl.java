package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.ReplanService;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * @author Admin
 * @ClassName: ReplanServiceImpl
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-23  14:34
 * @Version:
 */
@Service
@RequiredArgsConstructor
public class ReplanServiceImpl implements ReplanService {
    public ProductPublishAssistRequestDTO buildReplanRequest(ProductPublishAssistRequestDTO original, ProductPublishAssistVO firstResult) {
        ProductPublishAssistRequestDTO replanRequest = new ProductPublishAssistRequestDTO();
        BeanUtils.copyProperties(original, replanRequest);

        String description = safeText(original.getDescription());
        ProductOptimizeResponseVO optimizeResult = firstResult == null ? null : firstResult.getOptimizeResult();

        if (optimizeResult != null && StringUtils.hasText(optimizeResult.getOptimizedDescription())) {
            description = optimizeResult.getOptimizedDescription().trim();
        }

        description = description
                .replace("私下交易", "平台交易")
                .replace("先付款", "平台担保交易")
                .replace("微信转账", "平台内支付")
                .replace("vx", "平台私信");

        if (!description.contains("仅支持平台内交易")) {
            description = description + "。仅支持平台内交易，不接受私下转账。";
        }
        if (!description.contains("可当面验货")) {
            description = description + "。支持当面验货后再确认。";
        }

        if (description.length() > 2000) {
            description = description.substring(0, 2000);
        }

        replanRequest.setDescription(description);
        return replanRequest;
    }
    public boolean shouldReplan(ProductPublishAssistVO firstResult) {
        // 1) 基础空值保护
        if (firstResult == null) {
            return true;
        }
        ProductOptimizeResponseVO optimizeResult = firstResult.getOptimizeResult();
        if (optimizeResult == null) {
            return true;
        }
        // 2) 关键字段质量不足（标题/描述）
        String optimizedTitle = optimizeResult.getOptimizedTitle();
        if (!StringUtils.hasText(optimizedTitle) || optimizedTitle.trim().length() < 6) {
            return true;
        }
        String optimizedDescription = optimizeResult.getOptimizedDescription();
        if (!StringUtils.hasText(optimizedDescription) || optimizedDescription.trim().length() < 20) {
            return true;
        }
        // 3) 结果完整性不足（标签/价格建议）
        List<String> tags = optimizeResult.getTags();
        if (tags == null || tags.isEmpty()) {
            return true;
        }
        if (!StringUtils.hasText(firstResult.getPriceSuggestion())) {
            return true;
        }
        // 4) 风险与发布决策触发
        Integer riskScore = firstResult.getRiskScore();
        if (riskScore == null || riskScore < 60) {
            return true;
        }
        List<String> riskWarnings = firstResult.getRiskWarnings();
        if (riskWarnings == null || riskWarnings.isEmpty() || riskScore<60){
            return true;
        }
        Boolean canPublish = firstResult.getCanPublish();
        return canPublish == null || Boolean.FALSE.equals(canPublish);
    }
    public Boolean decideCanPublish(Integer riskScore) {
        return riskScore >= 60;
    }
    private String safeText(String text) {
        return StringUtils.hasText(text) ? text.trim() : "";
    }

}
