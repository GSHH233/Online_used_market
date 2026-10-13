package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.modules.ai.entity.L2MemoryRecord;
import org.example.online_used_marketplace.modules.ai.mapper.l2.L2MemoryRecordMapper;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallQuery;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryRecallResult;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryWriteCommand;
import org.example.online_used_marketplace.modules.ai.memory.L2MemoryMutationResult;
import org.example.online_used_marketplace.modules.ai.service.EmbeddingService;
import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;
import org.example.online_used_marketplace.modules.ai.vo.L2SessionSummaryWriteResult;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import java.util.List;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;

class L2MemoryServiceImplTest {

    @Test
    void blankUserIdFailsClosedForSaveAndRetrieve() {
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(mapper, embeddingService, new ObjectMapper());

        service.saveMemory("  ", "session-1", "FACT", "must not be shared", 0.3D);

        assertTrue(service.retrieve("\t", "session-1", "query", 5, 0.4D).isEmpty());
        verifyNoInteractions(mapper, embeddingService);
    }

    @Test
    void transientNoiseAndCredentialsAreRejectedBeforeAnyDatabaseWrite() {
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(mapper, embeddingService, new ObjectMapper(), jdbc);

        L2MemoryMutationResult noise = service.upsertSlotMemory(new L2MemoryWriteCommand(
                "user-1", "session-1", "FACT", "临时对话噪声 1", 0.1D, "{}",
                "event-noise", "USER_STATED", "CONFIRMED", "USER", "USER", "noise-1"));
        L2MemoryMutationResult credential = service.upsertSlotMemory(new L2MemoryWriteCommand(
                "user-1", "session-1", "FACT", "api_key=sk-example-secret", 0.8D, "{}",
                "event-secret", "USER_STATED", "CONFIRMED", "USER", "USER", "secret"));

        assertEquals("SKIPPED", noise.disposition());
        assertEquals("SKIPPED", credential.disposition());
        assertNull(noise.recordId());
        assertNull(credential.recordId());
        verifyNoInteractions(mapper, embeddingService, jdbc);
    }

    @Test
    void latestSessionSummaryMapsTheExactLookupResult() {
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        L2MemoryRecord latest = new L2MemoryRecord();
        latest.setId(42L);
        latest.setUserId("user-1");
        latest.setSessionId("session-1");
        latest.setMemoryType("SESSION_SUMMARY");
        latest.setContent("latest summary");
        latest.setMetadataJson("{\"source\":\"test\"}");
        latest.setImportance(0.8D);
        latest.setStatus("ACTIVE");
        when(mapper.selectOne(any())).thenReturn(latest);
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(mapper, mock(EmbeddingService.class), new ObjectMapper());

        L2MemoryHitVO result = service.latestSessionSummary(" user-1 ", " session-1 ");

        assertEquals(42L, result.getId());
        assertEquals("user-1", result.getUserId());
        assertEquals("session-1", result.getSessionId());
        assertEquals("SESSION_SUMMARY", result.getMemoryType());
        assertEquals("latest summary", result.getContent());
        assertEquals(0.8D, result.getImportanceScore());
        verify(mapper).selectOne(any());
    }

    @Test
    void latestSessionSummaryReturnsNullForMissingRecordOrScope() {
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(mapper, mock(EmbeddingService.class), new ObjectMapper());

        assertNull(service.latestSessionSummary("user-1", "session-1"));
        assertNull(service.latestSessionSummary("", "session-1"));
        verify(mapper).selectOne(any());
    }

    @Test
    void semanticRecallDoesNotIncludeOtherSessionsSummary() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), L2MemoryRecord.class);
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        L2MemoryRecord ownSummary = record("user-1", "session-1", "SESSION_SUMMARY", "当前相机摘要");
        L2MemoryRecord otherSummary = record("user-1", "session-2", "SESSION_SUMMARY", "旧手机摘要");
        L2MemoryRecord otherUser = record("user-2", "session-1", "FACT", "别人的事实");
        L2MemoryRecord longTerm = record("user-1", "session-2", "FACT", "长期偏好");
        when(mapper.selectList(any())).thenReturn(List.of(ownSummary, otherSummary, otherUser, longTerm));
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(mapper, mock(EmbeddingService.class), new ObjectMapper());

        List<L2MemoryHitVO> hits = service.retrieve("user-1", "session-1", "摘要", 10, 0D);
        assertEquals(List.of("当前相机摘要", "长期偏好"), hits.stream().map(L2MemoryHitVO::getContent).toList());

        hits = service.retrieve("user-1", "", "摘要", 10, 0D);
        assertEquals(List.of("长期偏好"), hits.stream().map(L2MemoryHitVO::getContent).toList());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<L2MemoryRecord>> queries = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper, times(2)).selectList(queries.capture());
        LambdaQueryWrapper<L2MemoryRecord> scoped = queries.getAllValues().get(0);
        assertTrue(scoped.getSqlSegment().contains("session_id"));
        assertTrue(scoped.getSqlSegment().contains("status"));
        assertTrue(scoped.getParamNameValuePairs().containsValue("session-1"));
        assertTrue(scoped.getParamNameValuePairs().containsValue("ACTIVE"));
        LambdaQueryWrapper<L2MemoryRecord> withoutSession = queries.getAllValues().get(1);
        assertTrue(withoutSession.getSqlSegment().contains("memory_type"));
        assertTrue(!withoutSession.getSqlSegment().contains("session_id"));
    }

    @Test
    void importanceCannotPromoteAnIrrelevantMemoryAcrossTheRelevanceGate() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), L2MemoryRecord.class);
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        L2MemoryRecord relevant = record("user-1", "session-1", "FACT", "蓝牙耳机电池续航说明");
        relevant.setId(11L);
        relevant.setImportance(0.1D);
        relevant.setEmbeddingJson("[]");
        L2MemoryRecord irrelevant = record("user-1", "session-1", "FACT", "用户喜欢详细回答");
        irrelevant.setId(12L);
        irrelevant.setImportance(0.8D);
        irrelevant.setEmbeddingJson("[]");
        when(mapper.selectCount(any())).thenReturn(2L);
        when(mapper.selectList(any())).thenReturn(List.of(irrelevant, relevant));
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(
                mapper, mock(EmbeddingService.class), new ObjectMapper());

        L2MemoryRecallResult result = service.recall(new L2MemoryRecallQuery(
                "user-1", "session-1", "蓝牙耳机", List.of("FACT"),
                5, 0.4D, 1000, true));

        assertEquals(List.of(11L), result.adoptedMemoryIds());
        assertEquals("LEXICAL_FALLBACK", result.retrievalMode());
        assertEquals("FORCED_LEXICAL_FALLBACK", result.fallbackReason());
        assertTrue(result.exclusions().stream().anyMatch(item ->
                Long.valueOf(12L).equals(item.memoryId())
                        && "BELOW_RELEVANCE_THRESHOLD".equals(item.reason())));
    }

    @Test
    void lexicalFallbackMatchesReorderedChinesePreferenceTerms() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), L2MemoryRecord.class);
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        L2MemoryRecord preference = record(
                "user-1", "session-1", "PREFERENCE", "用户偏好 Nintendo Switch 游戏主机");
        preference.setId(31L);
        preference.setEmbeddingJson("[]");
        when(mapper.selectCount(any())).thenReturn(1L);
        when(mapper.selectList(any())).thenReturn(List.of(preference));
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(
                mapper, mock(EmbeddingService.class), new ObjectMapper());

        L2MemoryRecallResult result = service.recall(new L2MemoryRecallQuery(
                "user-1", "session-1", "游戏主机偏好", List.of("PREFERENCE"),
                5, 0.2D, 1000, true));

        assertEquals(List.of(31L), result.adoptedMemoryIds());
        assertTrue(result.hits().get(0).getRelevanceScore() >= 0.2D);
    }

    @Test
    void lexicalFallbackNormalizesPreferenceIntentAtRuntimeThreshold() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), L2MemoryRecord.class);
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        L2MemoryRecord preference = record(
                "user-1", "session-1", "PREFERENCE", "用户关注 Fujifilm X-T5 相机");
        preference.setId(32L);
        preference.setEmbeddingJson("[]");
        when(mapper.selectCount(any())).thenReturn(1L);
        when(mapper.selectList(any())).thenReturn(List.of(preference));
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(
                mapper, mock(EmbeddingService.class), new ObjectMapper());

        L2MemoryRecallResult result = service.recall(new L2MemoryRecallQuery(
                "user-1", "session-1", "相机品牌偏好", List.of("PREFERENCE"),
                5, 0.3D, 1000, true));

        assertEquals(List.of(32L), result.adoptedMemoryIds());
        assertTrue(result.hits().get(0).getRelevanceScore() >= 0.3D);
    }

    @Test
    void recallReportsCandidateTruncationAndKeepsTypeFilterInTheDatabaseQuery() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), L2MemoryRecord.class);
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        L2MemoryRecord relevant = record("user-1", "session-1", "FACT", "折叠屏手机验机流程");
        relevant.setId(201L);
        relevant.setEmbeddingJson("[]");
        when(mapper.selectCount(any())).thenReturn(1001L);
        when(mapper.selectList(any())).thenReturn(List.of(relevant));
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(
                mapper, mock(EmbeddingService.class), new ObjectMapper());

        L2MemoryRecallResult result = service.recall(new L2MemoryRecallQuery(
                "user-1", "session-1", "折叠屏手机", List.of("FACT"),
                5, 0.4D, 1000, true));

        assertTrue(result.candidateTruncated());
        assertEquals(1001L, result.eligibleCandidateCount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<L2MemoryRecord>> queries = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectCount(queries.capture());
        assertTrue(queries.getValue().getSqlSegment().contains("memory_type"));
        assertTrue(queries.getValue().getParamNameValuePairs().containsValue("FACT"));
    }

    @Test
    void sessionSummaryCasAdvancesTheSlotWhenRevisionMatches() {
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            L2MemoryRecord record = invocation.getArgument(0);
            record.setId(77L);
            return 1;
        }).when(mapper).insert(any(L2MemoryRecord.class));
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1, 1);
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(
                mapper, mock(EmbeddingService.class), new ObjectMapper(), jdbc);

        L2SessionSummaryWriteResult result = service.saveSessionSummaryCas(
                "user-1", "session-1", "SESSION_SUMMARY|category=手机", 0.8D, "{}", 3L);

        assertTrue(result.accepted());
        assertEquals(4L, result.revision());
        assertEquals(77L, result.currentMemory().getId());
    }

    @Test
    void staleSessionSummaryCasCannotReplaceTheCurrentRecord() {
        L2MemoryRecordMapper mapper = mock(L2MemoryRecordMapper.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            L2MemoryRecord record = invocation.getArgument(0);
            record.setId(88L);
            return 1;
        }).when(mapper).insert(any(L2MemoryRecord.class));
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1, 0);
        when(jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(Map.of("current_record_id", 42L, "revision", 4L)));
        L2MemoryRecord current = record("user-1", "session-1", "SESSION_SUMMARY", "current");
        current.setId(42L);
        when(mapper.selectById(42L)).thenReturn(current);
        L2MemoryServiceImpl service = new L2MemoryServiceImpl(
                mapper, mock(EmbeddingService.class), new ObjectMapper(), jdbc);

        L2SessionSummaryWriteResult result = service.saveSessionSummaryCas(
                "user-1", "session-1", "SESSION_SUMMARY|stale", 0.8D, "{}", 3L);

        assertTrue(!result.accepted());
        assertEquals(4L, result.revision());
        assertEquals(42L, result.currentMemory().getId());
        verify(mapper).deleteById(88L);
    }

    private L2MemoryRecord record(String userId, String sessionId, String type, String content) {
        L2MemoryRecord record = new L2MemoryRecord();
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setMemoryType(type);
        record.setContent(content);
        record.setImportance(0.3D);
        record.setStatus("ACTIVE");
        return record;
    }
}
