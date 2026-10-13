package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.modules.ai.config.ChatMemoryProperties;
import org.example.online_used_marketplace.modules.ai.memory.MemoryScope;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationRequest;
import org.example.online_used_marketplace.modules.ai.token.TokenGenerationResult;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContext;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentRuntimeContextBuilder;
import org.example.online_used_marketplace.modules.ai.agent.runtime.AgentExecutionIdentity;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.service.MemoryGraphService;
import org.example.online_used_marketplace.modules.ai.service.UserProfileService;
import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;

class MemoryScopeIsolationTest {

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameSessionCannotReadAnotherUsersRecentTurns() {
        InMemoryConversationMemoryServiceImpl service = new InMemoryConversationMemoryServiceImpl(new ChatMemoryProperties());
        MemoryScope alice = new MemoryScope("1", "shared-session");
        MemoryScope bob = new MemoryScope("2", "shared-session");
        service.appendTurn(alice, "我的相机", "相机建议");
        service.appendTurn(bob, "我的手机", "手机建议");

        assertThat(service.getRecentMessages(alice, 10)).containsExactly("Q: 我的相机", "A: 相机建议");
        assertThat(service.getRecentMessages(bob, 10)).containsExactly("Q: 我的手机", "A: 手机建议");
        assertThat(service.getRecentMessages(null, 10)).isEmpty();
    }

    @Test
    void scopeComesFromAuthenticatedPrincipal() {
        assertThat(MemoryScope.current("shared-session")).isNull();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(42L, "alice", "USER"), null, List.of()));
        assertThat(MemoryScope.current("shared-session")).isEqualTo(new MemoryScope("42", "shared-session"));
    }

    @Test
    void summaryRestoreRequiresExactOwnerAndSession() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        InMemorySessionSummaryServiceImpl service = new InMemorySessionSummaryServiceImpl(
                mock(ChatGenerationService.class), l2, new ObjectMapper());
        L2MemoryHitVO hit = new L2MemoryHitVO();
        hit.setUserId("1");
        hit.setSessionId("shared-session");
        hit.setMemoryType("SESSION_SUMMARY");
        hit.setMetadataJson("{\"kv\":{\"category\":\"相机\"}}");
        when(l2.latestSessionSummary("1", "shared-session")).thenReturn(hit);

        assertThat(service.getSummary(new MemoryScope("1", "shared-session"))).contains("category=相机");
        assertThat(service.getSummary(new MemoryScope("2", "shared-session"))).isEmpty();
    }

    @Test
    void summaryRestoreFallsBackToPersistedTextWithoutKv() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        L2MemoryHitVO hit = new L2MemoryHitVO();
        hit.setUserId("1");
        hit.setSessionId("session-a");
        hit.setMemoryType("SESSION_SUMMARY");
        hit.setContent("SESSION_SUMMARY|category=相机");
        hit.setMetadataJson("{}");
        when(l2.latestSessionSummary("1", "session-a")).thenReturn(hit);
        InMemorySessionSummaryServiceImpl service = new InMemorySessionSummaryServiceImpl(
                mock(ChatGenerationService.class), l2, new ObjectMapper());

        assertThat(service.getSummary(new MemoryScope("1", "session-a"))).isEqualTo("category=相机");
    }

    @Test
    void twoSessionsRestoreTheirOwnSummaryWithoutUsingSemanticFacts() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        L2MemoryHitVO camera = summary("1", "session-a", "相机");
        L2MemoryHitVO phone = summary("1", "session-b", "手机");
        when(l2.latestSessionSummary("1", "session-a")).thenReturn(camera);
        when(l2.latestSessionSummary("1", "session-b")).thenReturn(phone);
        InMemorySessionSummaryServiceImpl service = new InMemorySessionSummaryServiceImpl(
                mock(ChatGenerationService.class), l2, new ObjectMapper());

        assertThat(service.getAutofillParams(new MemoryScope("1", "session-a"))).containsEntry("category", "相机");
        assertThat(service.getAutofillParams(new MemoryScope("1", "session-b"))).containsEntry("category", "手机");
        assertThat(service.getSummary(new MemoryScope("1", "session-a"))).doesNotContain("手机");
        verify(l2, never()).retrieve(any(), any(), any(), anyInt(), anyDouble());
    }

    @Test
    void explicitProductSwitchDropsOldProductFieldsButFollowUpKeepsThem() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        InMemorySessionSummaryServiceImpl service = new InMemorySessionSummaryServiceImpl(
                mock(ChatGenerationService.class), l2, new ObjectMapper());
        MemoryScope scope = new MemoryScope("1", "session-a");
        service.refreshSummaryIfNeeded(scope, "iPhone 13 手机无发票怎么定价？", "请如实描述机况。");
        service.refreshSummaryIfNeeded(scope, "改问这台手机的标题怎么写？", "标题需体现机况。");
        assertThat(service.getAutofillParams(scope)).containsEntry("model", "iPhone 13");

        service.refreshSummaryIfNeeded(scope, "现在换成华为手机，帮我写标题。", "标题应如实描述新手机。");

        assertThat(service.getAutofillParams(scope)).containsEntry("brand", "Huawei")
                .doesNotContainKey("model");
        assertThat(service.getStructuredSummary(scope).getKnownFacts()).doesNotContain("无发票");

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(l2, times(3)).saveMemory(any(), any(), any(), any(), any(), metadata.capture());
        L2MemoryHitVO persisted = summary("1", "session-a", "手机");
        persisted.setMetadataJson(metadata.getValue());
        when(l2.latestSessionSummary("1", "session-a")).thenReturn(persisted);
        InMemorySessionSummaryServiceImpl restored = new InMemorySessionSummaryServiceImpl(
                mock(ChatGenerationService.class), l2, new ObjectMapper());
        assertThat(restored.getAutofillParams(scope)).containsEntry("brand", "Huawei")
                .doesNotContainKey("model");
        assertThat(restored.getStructuredSummary(scope).getKnownFacts()).doesNotContain("无发票");
    }

    @Test
    void productSwitchOnLlmRefreshCannotReintroduceOldProductFacts() {
        ChatGenerationService generation = mock(ChatGenerationService.class);
        when(generation.generate(any(TokenGenerationRequest.class))).thenReturn(TokenGenerationResult.builder()
                .content("{\"model\":\"iPhone 13\",\"brand\":\"Apple\",\"knownFacts\":[\"无发票\"]}")
                .build());
        InMemorySessionSummaryServiceImpl service = new InMemorySessionSummaryServiceImpl(
                generation, mock(L2MemoryService.class), new ObjectMapper());
        MemoryScope scope = new MemoryScope("1", "session-a");
        service.refreshSummaryIfNeeded(scope, "iPhone 13 手机无发票怎么定价？", "请如实描述。");
        service.refreshSummaryIfNeeded(scope, "继续说这台手机的标题。", "标题简洁即可。");

        service.refreshSummaryIfNeeded(scope, "现在换成华为手机，帮我写标题。", "不要沿用旧型号。");

        assertThat(service.getAutofillParams(scope)).containsEntry("brand", "Huawei")
                .doesNotContainKey("model");
        assertThat(service.getStructuredSummary(scope).getKnownFacts()).doesNotContain("无发票");
    }

    private L2MemoryHitVO summary(String userId, String sessionId, String category) {
        L2MemoryHitVO hit = new L2MemoryHitVO();
        hit.setUserId(userId);
        hit.setSessionId(sessionId);
        hit.setMemoryType("SESSION_SUMMARY");
        hit.setMetadataJson("{\"kv\":{\"category\":\"" + category + "\"}}");
        return hit;
    }

    @Test
    void failedSummaryWriteIsNotCachedAsPersisted() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(l2).saveMemory(any(), any(), any(), any(), any(), any());
        InMemorySessionSummaryServiceImpl service = new InMemorySessionSummaryServiceImpl(
                mock(ChatGenerationService.class), l2, new ObjectMapper());
        MemoryScope scope = new MemoryScope("1", "session-a");

        assertThatThrownBy(() -> service.refreshSummaryIfNeeded(scope, "相机价格", "请看成色"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.getStructuredSummary(scope)).isNull();
    }

    @Test
    void unauthenticatedRuntimeNeverQueriesPrivateMemory() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        MemoryGraphService graph = mock(MemoryGraphService.class);
        UserProfileService profile = mock(UserProfileService.class);
        AgentRuntimeContextBuilder builder = new AgentRuntimeContextBuilder(
                mock(org.example.online_used_marketplace.modules.ai.service.ConversationMemoryService.class),
                l2, mock(org.example.online_used_marketplace.modules.ai.service.SessionSummaryService.class),
                profile, graph);

        AgentRuntimeContext context = builder.build("react", "shared-session", "trace-1", "INTERNAL", "问题", 3);

        assertThat(context.getUserId()).isEmpty();
        assertThat(context.getL1RecentMessages()).isEmpty();
        assertThat(context.getL2MemoryLines()).isEmpty();
        assertThat(context.getRole()).isEqualTo("USER");
        assertThat(context.getMode()).isEqualTo("USER");
        verifyNoInteractions(l2, graph, profile);
    }

    @Test
    void persistedInternalIdentityWorksWithoutThreadLocalSecurityContext() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        MemoryGraphService graph = mock(MemoryGraphService.class);
        UserProfileService profile = mock(UserProfileService.class);
        org.example.online_used_marketplace.modules.ai.service.SessionSummaryService summary =
                mock(org.example.online_used_marketplace.modules.ai.service.SessionSummaryService.class);
        when(summary.getSummary(new MemoryScope("42", "async-session"))).thenReturn("model=iPhone 13");
        when(l2.recall(any())).thenReturn(
                org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallResult.empty(
                        1000, "EMPTY", "NO_ELIGIBLE_CANDIDATES"));
        AgentRuntimeContextBuilder builder = new AgentRuntimeContextBuilder(
                mock(org.example.online_used_marketplace.modules.ai.service.ConversationMemoryService.class),
                l2, summary, profile, graph);

        AgentRuntimeContext context = builder.build(
                "graph", "async-session", "trace-1", "INTERNAL", "检查发布风险", 4,
                new AgentExecutionIdentity("42", "INTERNAL"));

        assertThat(context.getUserId()).isEqualTo("42");
        assertThat(context.getRole()).isEqualTo("INTERNAL");
        assertThat(context.getMode()).isEqualTo("INTERNAL");
        assertThat(context.getL3Summary()).contains("iPhone 13");
        verify(l2).recall(argThat(query ->
                "42".equals(query.userId())
                        && "async-session".equals(query.sessionId())
                        && "检查发布风险".equals(query.query())
                        && query.topK() == 4
                        && query.relevanceThreshold() == 0.3D));
    }

    @Test
    void persistedUserIdentityCannotUpgradeToInternalMode() {
        AgentRuntimeContextBuilder builder = new AgentRuntimeContextBuilder(
                mock(org.example.online_used_marketplace.modules.ai.service.ConversationMemoryService.class),
                mock(L2MemoryService.class),
                mock(org.example.online_used_marketplace.modules.ai.service.SessionSummaryService.class),
                mock(UserProfileService.class), mock(MemoryGraphService.class));

        AgentRuntimeContext context = builder.build(
                "graph", "async-session", "trace-1", "INTERNAL", "检查发布风险", 4,
                new AgentExecutionIdentity("42", "USER"));

        assertThat(context.getUserId()).isEqualTo("42");
        assertThat(context.getMode()).isEqualTo("USER");
        assertThat(context.getRole()).isEqualTo("USER");
    }

    @Test
    void laterTurnDoesNotLoseItsUpdateWhenEarlierExtractionIsSlow() throws Exception {
        ChatGenerationService generation = mock(ChatGenerationService.class);
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch releaseExtraction = new CountDownLatch(1);
        when(generation.generate(any(TokenGenerationRequest.class))).thenAnswer(invocation -> {
            extractionStarted.countDown();
            if (!releaseExtraction.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test extractor timed out");
            }
            throw new IllegalStateException("test extractor unavailable");
        });
        InMemorySessionSummaryServiceImpl service = new InMemorySessionSummaryServiceImpl(
                generation, mock(L2MemoryService.class), new ObjectMapper());
        MemoryScope scope = new MemoryScope("1", "shared-session");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.refreshSummaryIfNeeded(scope, "相机怎么定价", "需要看成色"));
            assertThat(extractionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.refreshSummaryIfNeeded(scope, "改问手机价格", "需要看电池"));
            releaseExtraction.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(service.getStructuredSummary(scope).getCategory()).isEqualTo("手机");
        } finally {
            releaseExtraction.countDown();
            executor.shutdownNow();
        }
    }
}
