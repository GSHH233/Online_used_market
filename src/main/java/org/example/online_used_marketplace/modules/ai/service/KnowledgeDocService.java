package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocCreateRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocGovernanceUpdateDTO;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeDocVO;

import java.util.List;
import java.util.Map;

/**
 * @author Admin
 * @ClassName: KnowledgeDocService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-25  23:54
 * @Version:
 */
public interface KnowledgeDocService {
    KnowledgeDocVO createDoc(KnowledgeDocCreateRequestDTO dto);
    KnowledgeDoc getByIdOrThrow(Long docId);
    KnowledgeDocVO updateGovernance(Long docId, KnowledgeDocGovernanceUpdateDTO dto);




}
