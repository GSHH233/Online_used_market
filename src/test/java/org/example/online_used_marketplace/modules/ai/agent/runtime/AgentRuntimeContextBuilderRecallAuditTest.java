package org.example.online_used_marketplace.modules.ai.agent.runtime;

import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallExclusion;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallResult;
import org.example.online_used_marketplace.modules.ai.service.ConversationMemoryService;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.service.MemoryGraphService;
import org.example.online_used_marketplace.modules.ai.service.SessionSummaryService;
import org.example.online_used_marketplace.modules.ai.service.UserProfileService;
import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeContextBuilderRecallAuditTest {

    @Test
    void contextAuditContainsIdsAndReasonsWithoutMemoryPlaintext() {
        L2MemoryService l2 = mock(L2MemoryService.class);
        L2MemoryHitVO hit = new L2MemoryHitVO();
        hit.setId(21L);
        hit.setMemoryType("FACT");
        hit.setContent("用户当前在学习 Go 后端");
        hit.setScore(0.91D);
        hit.setImportanceScore(0.3D);
        when(l2.recall(any())).thenReturn(new L2MemoryRecallResult(
                List.of(hit), 12L, 12, 1000, false,
                "LEXICAL_FALLBACK", "EMBEDDING_UNAVAILABLE", List.of(21L),
                List.of(new L2MemoryRecallExclusion(22L, "PREFERENCE", "BELOW_RELEVANCE_THRESHOLD"))));
        AgentRuntimeContextBuilder builder = new AgentRuntimeContextBuilder(
                mock(ConversationMemoryService.class), l2, mock(SessionSummaryService.class),
                mock(UserProfileService.class), mock(MemoryGraphService.class));

        AgentRuntimeContext context = builder.build(
                "react", "session-1", "trace-1", "INTERNAL", "规划 Go 后端路线", 4,
                new AgentExecutionIdentity("42", "INTERNAL"));

        assertThat(context.getPromptBlock()).contains("用户当前在学习 Go 后端");
        assertThat(context.getL2RecallAudit()).containsEntry("retrievalMode", "LEXICAL_FALLBACK")
                .containsEntry("fallbackReason", "EMBEDDING_UNAVAILABLE")
                .containsEntry("adoptedMemoryIds", List.of(21L));
        assertThat(context.getL2RecallAudit().toString()).doesNotContain("用户当前在学习 Go 后端");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadataAudit = (Map<String, Object>) context.getMetadata().get("l2Recall");
        assertThat(metadataAudit).containsEntry("candidateTruncated", false);
    }
}
