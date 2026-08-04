package org.example.online_used_marketplace.modules.ai.service.impl;

import org.example.online_used_marketplace.modules.ai.config.TokenGovernanceProperties;
import org.example.online_used_marketplace.modules.ai.service.TokenCounterService;
import org.example.online_used_marketplace.modules.ai.token.TokenBudgetResult;
import org.example.online_used_marketplace.modules.ai.token.TokenPromptSection;
import org.example.online_used_marketplace.modules.ai.token.TokenScene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenGovernanceServiceTest {

    private final TokenCounterService tokenCounter = new Cl100kTokenCounterService();

    @Test
    void trimsLowPriorityMemoryBeforeQuestionAndEvidence() {
        TokenGovernanceProperties properties = new TokenGovernanceProperties();
        properties.setRagChatInputTokens(180);
        TokenGovernanceServiceImpl service = new TokenGovernanceServiceImpl(tokenCounter, properties);

        TokenBudgetResult result = service.assemble(TokenScene.RAG_CHAT, List.of(
                TokenPromptSection.of("回答约束", "只能依据证据回答，不得编造。", 100, true),
                TokenPromptSection.of("本轮问题", "RTX3060 显卡无发票能发布吗？", 100, true),
                TokenPromptSection.of("RAG证据", "无发票商品必须披露来源，不得宣称官方全新。".repeat(4), 95, true),
                TokenPromptSection.of("最近会话上下文", "用户之前讨论了相机、无人机和手机。".repeat(80), 20, false)
        ));

        assertThat(result.getPromptTokens()).isLessThanOrEqualTo(180);
        assertThat(result.getPrompt()).contains("RTX3060", "无发票商品必须披露来源");
        assertThat(result.getTrimmedSections()).contains("最近会话上下文");
        assertThat(result.getTrimmedTokens()).isPositive();
    }

    @Test
    void keepsOriginalOrderAfterPriorityBasedTrimming() {
        TokenGovernanceProperties properties = new TokenGovernanceProperties();
        properties.setAgentFinalInputTokens(150);
        TokenGovernanceServiceImpl service = new TokenGovernanceServiceImpl(tokenCounter, properties);

        TokenBudgetResult result = service.assemble(TokenScene.AGENT_FINAL, List.of(
                TokenPromptSection.of("规则", "先给结论。", 100, true),
                TokenPromptSection.of("问题", "为什么接口延迟升高？", 100, true),
                TokenPromptSection.of("Observation", "Prometheus P95 升高，日志存在连接池等待。".repeat(5), 90, true),
                TokenPromptSection.of("历史偏好", "用户喜欢详细回答。".repeat(80), 10, false)
        ));

        assertThat(result.getPrompt().indexOf("## 规则"))
                .isLessThan(result.getPrompt().indexOf("## 问题"));
        assertThat(result.getPrompt().indexOf("## 问题"))
                .isLessThan(result.getPrompt().indexOf("## Observation"));
        assertThat(result.getPromptTokens()).isLessThanOrEqualTo(150);
    }

    @Test
    void reportsNoTrimmingWhenPromptFitsBudget() {
        TokenGovernanceProperties properties = new TokenGovernanceProperties();
        TokenGovernanceServiceImpl service = new TokenGovernanceServiceImpl(tokenCounter, properties);

        TokenBudgetResult result = service.fit(TokenScene.AGENT_PLANNER, "只调用只读工具。");

        assertThat(result.isTrimmed()).isFalse();
        assertThat(result.getTrimmedTokens()).isZero();
        assertThat(result.getOutputBudget()).isEqualTo(properties.getAgentPlannerOutputTokens());
    }
}
