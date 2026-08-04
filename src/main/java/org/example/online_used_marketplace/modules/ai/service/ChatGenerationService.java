package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.token.TokenGenerationRequest;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationResult;

/**
 * 统一的模型生成出口。旧接口保留兼容，治理链路使用结构化请求获取 Token 用量。
 */
public interface ChatGenerationService {

    String generate(String prompt);

    TokenGenerationResult generate(TokenGenerationRequest request);
}
