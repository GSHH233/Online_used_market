package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.service.TokenCounterService;
import org.example.online_used_marketplace.modules.ai.service.TokenGovernanceService;
import org.example.online_used_marketplace.modules.ai.service.TokenUsageService;
import org.example.online_used_marketplace.modules.ai.token.TokenBudgetResult;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationRequest;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationResult;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.example.online_used_marketplace.modules.ai.token.TokenUsageRecord;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

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
    private final TokenGovernanceService tokenGovernanceService;
    private final TokenCounterService tokenCounterService;
    private final TokenUsageService tokenUsageService;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String configuredModel;

    @Override
    public String generate(String prompt) {
        return generate(TokenGenerationRequest.builder()
                .scene(TokenScene.GENERAL)
                .prompt(prompt)
                .build()).getContent();
    }

    @Override
    public TokenGenerationResult generate(TokenGenerationRequest request) {
        TokenGenerationRequest normalized = request == null
                ? TokenGenerationRequest.builder().scene(TokenScene.GENERAL).prompt("").build()
                : request;
        TokenScene scene = normalized.getScene() == null ? TokenScene.GENERAL : normalized.getScene();
        TokenBudgetResult budget = normalized.getBudgetResult() == null
                ? tokenGovernanceService.fit(scene, normalized.getPrompt())
                : normalized.getBudgetResult();
        String prompt = budget.getPrompt();
        String systemPrompt = StringUtils.hasText(normalized.getSystemPrompt())
                ? normalized.getSystemPrompt().trim()
                : SYSTEM_PROMPT;
        long startedAt = System.currentTimeMillis();
        Exception lastError = null;
        int attempts = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attempts = attempt;
            try {
                ChatResponse response = chatClient
                        .build()
                        .prompt()
                        .system(systemPrompt)
                        .user(prompt)
                        .options(ChatOptions.builder().maxTokens(budget.getOutputBudget()).build())
                        .call()
                        .chatResponse();
                String content = response == null || response.getResult() == null
                        ? ""
                        : response.getResult().getOutput().getText();
                Usage usage = response == null || response.getMetadata() == null
                        ? null
                        : response.getMetadata().getUsage();
                int estimatedPromptTokens = tokenCounterService.count(systemPrompt) + tokenCounterService.count(prompt);
                int estimatedCompletionTokens = tokenCounterService.count(content);
                boolean estimated = usage == null
                        || usage.getPromptTokens() == null
                        || usage.getCompletionTokens() == null;
                int promptTokens = estimated ? estimatedPromptTokens : Math.max(0, usage.getPromptTokens());
                int completionTokens = estimated ? estimatedCompletionTokens : Math.max(0, usage.getCompletionTokens());
                String model = response != null && response.getMetadata() != null
                        && StringUtils.hasText(response.getMetadata().getModel())
                        ? response.getMetadata().getModel()
                        : configuredModel;
                TokenGenerationResult result = TokenGenerationResult.builder()
                        .content(content)
                        .traceId(normalized.getTraceId())
                        .scene(scene)
                        .model(model)
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(promptTokens + completionTokens)
                        .inputBudget(budget.getInputBudget())
                        .outputBudget(budget.getOutputBudget())
                        .originalPromptTokens(budget.getOriginalPromptTokens())
                        .trimmedTokens(budget.getTrimmedTokens())
                        .usageEstimated(estimated)
                        .attempts(attempt)
                        .elapsedMs(System.currentTimeMillis() - startedAt)
                        .build();
                record(result, "SUCCESS", "");
                return result;
            } catch (BusinessException e) {
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
        TokenGenerationResult failed = TokenGenerationResult.builder()
                .traceId(normalized.getTraceId())
                .scene(scene)
                .model(configuredModel)
                .promptTokens(tokenCounterService.count(systemPrompt) + tokenCounterService.count(prompt))
                .completionTokens(0)
                .totalTokens(tokenCounterService.count(systemPrompt) + tokenCounterService.count(prompt))
                .inputBudget(budget.getInputBudget())
                .outputBudget(budget.getOutputBudget())
                .originalPromptTokens(budget.getOriginalPromptTokens())
                .trimmedTokens(budget.getTrimmedTokens())
                .usageEstimated(true)
                .attempts(attempts)
                .elapsedMs(System.currentTimeMillis() - startedAt)
                .build();
        record(failed, "FAILED", lastError == null ? "" : lastError.getMessage());
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "AI服务暂时不可用，请稍后重试");
    }

    private void record(TokenGenerationResult result, String status, String errorMessage) {
        tokenUsageService.record(TokenUsageRecord.builder()
                .traceId(result.getTraceId())
                .scene(result.getScene())
                .model(result.getModel())
                .promptTokens(result.getPromptTokens())
                .completionTokens(result.getCompletionTokens())
                .totalTokens(result.getTotalTokens())
                .inputBudget(result.getInputBudget())
                .outputBudget(result.getOutputBudget())
                .originalPromptTokens(result.getOriginalPromptTokens())
                .trimmedTokens(result.getTrimmedTokens())
                .usageEstimated(result.isUsageEstimated())
                .attempts(result.getAttempts())
                .elapsedMs(result.getElapsedMs())
                .status(status)
                .errorMessage(errorMessage)
                .createdTime(LocalDateTime.now())
                .build());
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
