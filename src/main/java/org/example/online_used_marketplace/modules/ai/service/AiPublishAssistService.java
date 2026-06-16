package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;

/**
 * @author Admin
 * @ClassName: AiPublishAssistService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-21  19:38
 * @Version:
 */
public interface AiPublishAssistService {
    ProductPublishAssistVO assist(ProductPublishAssistRequestDTO request);
}
