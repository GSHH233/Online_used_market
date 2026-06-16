package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.ProductOptimizeRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ProductOptimizeResponseVO;

/**
 * @author Admin
 * @ClassName: AiProductService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-20  20:26
 * @Version:
 */
public interface AiProductService {

    /**
     * 优化商品文案信息（标题、描述、标签、风险提示）
     */
    ProductOptimizeResponseVO optimizeProductInfo(ProductOptimizeRequestDTO request);
}
