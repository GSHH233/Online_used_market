package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * @author Admin
 * @ClassName: ChatGenerationServiceImpl
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-05-06  13:47
 * @Version:
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatGenerationServiceImpl implements ChatGenerationService {
    private static final String SYSTEM_PROMPT = """
            你是风控与发布助手，只能基于用户提供的证据回答。
            如果证据不足，不能编造具体结论或价格；但需要先说明证据里能确定的风险点、影响因素或可执行建议，再说明还缺哪些信息。
            回答保持简洁，优先给出可执行建议。
            """;
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 300L;

    private final ChatClient.Builder chatClient;

    @Override
    public String generate(String prompt) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return chatClient
                        .build()
                        .prompt()
                        .system(SYSTEM_PROMPT)
                        .user(prompt)
                        .call()
                        .content();
            } catch (BusinessException e) {
                // 参数校验、解析校验等业务异常，原样抛出
                throw e;
            } catch (Exception e) {
                lastError = e;
                if (attempt >= MAX_ATTEMPTS) {
                    break;
                }
                log.warn("AI generation attempt {}/{} failed, retrying, message={}", attempt, MAX_ATTEMPTS, e.getMessage());
                sleepQuietly(BASE_BACKOFF_MS * attempt);
            }
        }
        log.error("AI generation failed after {} attempts, message={}", MAX_ATTEMPTS,
                lastError == null ? "" : lastError.getMessage(), lastError);
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "AI服务暂时不可用，请稍后重试");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
