package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.ProductPublishAssistRequestDTO;
import org.example.online_used_marketplace.modules.ai.vo.AssistStepTraceVO;
import org.example.online_used_marketplace.modules.ai.vo.ProductPublishAssistVO;

import java.util.List;
import java.util.Optional;

/**
 * 发布助手入口防护服务：处理不适合进入完整 Agent 编排的确定性澄清场景。
 */
public interface PublishAssistGuardrailService {

    Optional<ProductPublishAssistVO> apply(ProductPublishAssistRequestDTO request, List<AssistStepTraceVO> trace);
}
