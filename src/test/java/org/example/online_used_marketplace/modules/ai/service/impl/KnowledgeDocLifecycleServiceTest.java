package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocCreateRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocGovernanceUpdateDTO;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeDocMapper;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeDocVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeDocLifecycleServiceTest {

    @Test
    void newDocumentStartsAsManagedDraftWithStableIdentity() {
        KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mapper.insert(any(KnowledgeDoc.class))).thenAnswer(invocation -> {
            KnowledgeDoc doc = invocation.getArgument(0);
            doc.setId(101L);
            return 1;
        });
        KnowledgeDocServiceImpl service = new KnowledgeDocServiceImpl(mapper, properties());
        KnowledgeDocCreateRequestDTO dto = new KnowledgeDocCreateRequestDTO();
        dto.setTitle("手机无发票发布规则");
        dto.setContent("无发票商品必须补充购买凭证和激活信息。");
        dto.setSourceType(KnowledgeSourceType.RULE);

        KnowledgeDocVO created = service.createDoc(dto);

        assertEquals(KnowledgeDocStatus.DRAFT, created.getStatus());
        assertTrue(Boolean.TRUE.equals(created.getLifecycleManaged()));
        assertEquals(1, created.getRevision());
        assertNotNull(created.getLogicalKnowledgeId());
        assertEquals(64, created.getContentHash().length());
    }

    @Test
    void activatingNewRevisionRetiresPreviousActiveRevision() {
        KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
        KnowledgeDoc current = new KnowledgeDoc();
        current.setId(2L);
        current.setTitle("显卡披露规则");
        current.setContent("新版规则");
        current.setSourceType(KnowledgeSourceType.RULE.name());
        current.setLogicalKnowledgeId("rule:gpu-history");
        current.setRevision(2);
        current.setStatus(KnowledgeDocStatus.DRAFT.name());
        KnowledgeDoc previous = new KnowledgeDoc();
        previous.setId(1L);
        previous.setLogicalKnowledgeId("rule:gpu-history");
        previous.setStatus(KnowledgeDocStatus.ACTIVE.name());
        when(mapper.selectById(2L)).thenReturn(current);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(previous));
        when(mapper.updateById(any(KnowledgeDoc.class))).thenReturn(1);
        KnowledgeDocServiceImpl service = new KnowledgeDocServiceImpl(mapper, properties());

        List<Long> retired = service.activateIndexedVersion(2L, "idx-v2");

        assertEquals(List.of(1L), retired);
        assertEquals(KnowledgeDocStatus.ACTIVE.name(), current.getStatus());
        assertEquals("idx-v2", current.getActiveIndexVersion());
        assertEquals(KnowledgeDocStatus.RETIRED.name(), previous.getStatus());
        assertEquals("SUPERSEDED_BY:2", previous.getDisabledReason());
        assertNotNull(previous.getRetiredAt());
        assertNotNull(previous.getRetentionUntil());
        assertEquals("PENDING", previous.getCleanupStatus());
        assertEquals(7, java.time.Duration.between(previous.getRetiredAt(), previous.getRetentionUntil()).toDays());
    }

    @Test
    void retiredRevisionCannotBeReactivatedFromGovernancePatch() {
        KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
        KnowledgeDoc retired = new KnowledgeDoc();
        retired.setId(7L);
        retired.setStatus(KnowledgeDocStatus.RETIRED.name());
        retired.setActiveIndexVersion("idx-old");
        retired.setSourceType(KnowledgeSourceType.RULE.name());
        when(mapper.selectById(7L)).thenReturn(retired);
        KnowledgeDocServiceImpl service = new KnowledgeDocServiceImpl(mapper, properties());
        KnowledgeDocGovernanceUpdateDTO request = new KnowledgeDocGovernanceUpdateDTO();
        request.setStatus(KnowledgeDocStatus.ACTIVE.name());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateGovernance(7L, request));

        assertTrue(error.getMessage().contains("受控回滚"));
    }

    @Test
    void governancePatchCannotBypassIndexVerificationToActivateDraft() {
        KnowledgeDocMapper mapper = mock(KnowledgeDocMapper.class);
        KnowledgeDoc draft = new KnowledgeDoc();
        draft.setId(8L);
        draft.setStatus(KnowledgeDocStatus.DRAFT.name());
        draft.setSourceType(KnowledgeSourceType.RULE.name());
        when(mapper.selectById(8L)).thenReturn(draft);
        KnowledgeDocServiceImpl service = new KnowledgeDocServiceImpl(mapper, properties());
        KnowledgeDocGovernanceUpdateDTO request = new KnowledgeDocGovernanceUpdateDTO();
        request.setStatus(KnowledgeDocStatus.ACTIVE.name());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateGovernance(8L, request));

        assertTrue(error.getMessage().contains("三存储验证"));
    }

    private KnowledgeIngestionProperties properties() {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getLifecycle().setRetiredRetentionDays(7);
        return properties;
    }
}
