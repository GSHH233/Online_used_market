package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocCreateRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocGovernanceUpdateDTO;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeDocVO;

import java.util.List;

/**
 * @author Admin
 * @ClassName: KnowledgeDocService
 * @Description: TODO(这里用一句话描述这个类的作用)
 * @date 2026-04-25  23:54
 * @Version:
 */
public interface KnowledgeDocService {
    KnowledgeDocVO createDoc(KnowledgeDocCreateRequestDTO dto);
    KnowledgeDocVO getVO(Long docId);
    KnowledgeDoc getByIdOrThrow(Long docId);
    KnowledgeDocVO updateGovernance(Long docId, KnowledgeDocGovernanceUpdateDTO dto);
    List<KnowledgeDocVO> list(String keyword, KnowledgeDocStatus status,
                              KnowledgeSourceType sourceType, Integer limit);
}
