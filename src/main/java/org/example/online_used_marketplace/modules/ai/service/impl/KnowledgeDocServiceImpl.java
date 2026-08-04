package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocCreateRequestDTO;
import org.example.online_used_marketplace.modules.ai.dto.KnowledgeDocGovernanceUpdateDTO;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeDocStatus;
import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.mapper.KnowledgeDocMapper;
import org.example.online_used_marketplace.modules.ai.service.KnowledgeDocService;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeDocVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 知识文档服务实现
 */
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class KnowledgeDocServiceImpl implements KnowledgeDocService {
    private final KnowledgeDocMapper knowledgeDocMapper;

    @Override
    public KnowledgeDocVO createDoc(KnowledgeDocCreateRequestDTO dto) {
        if (dto == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
        if (!StringUtils.hasText(dto.getTitle())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "标题不能为空");
        }
        if (!StringUtils.hasText(dto.getContent())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "内容不能为空");
        }
        if (dto.getSourceType() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "数据类型不能为空");
        }

        LocalDateTime now = LocalDateTime.now();

        KnowledgeDoc knowledgeDoc = new KnowledgeDoc();
        knowledgeDoc.setTitle(dto.getTitle().trim());
        knowledgeDoc.setContent(dto.getContent().trim());
        knowledgeDoc.setSourceType(dto.getSourceType().name());
        knowledgeDoc.setSourceUri(dto.getSourceUri());
        knowledgeDoc.setFileName(dto.getFileName());
        knowledgeDoc.setMimeType(dto.getMimeType());
        knowledgeDoc.setFileHash(dto.getFileHash());
        knowledgeDoc.setParserType(dto.getParserType());
        knowledgeDoc.setParserVersion(dto.getParserVersion());
        knowledgeDoc.setStructureJson(dto.getStructureJson());
        knowledgeDoc.setChunkStrategyVersion(dto.getChunkStrategyVersion());
        knowledgeDoc.setEmbeddingModel(dto.getEmbeddingModel());
        knowledgeDoc.setActiveIndexVersion(null);
        knowledgeDoc.setTags(dto.getTags());
        knowledgeDoc.setSourceTrustLevel(normalizeTrustLevel(dto.getSourceTrustLevel()));
        knowledgeDoc.setEffectiveFrom(dto.getEffectiveFrom());
        knowledgeDoc.setEffectiveTo(dto.getEffectiveTo());
        knowledgeDoc.setDocVersion(StringUtils.hasText(dto.getDocVersion()) ? dto.getDocVersion().trim() : "v1");
        knowledgeDoc.setStatus(KnowledgeDocStatus.DRAFT.name());
        knowledgeDoc.setCreatedTime(now);
        knowledgeDoc.setUpdatedTime(now);

        int insert = knowledgeDocMapper.insert(knowledgeDoc);
        if (insert <= 0) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "创建知识文档失败");
        }

        return toVO(knowledgeDoc);
    }

    @Override
    public KnowledgeDocVO updateGovernance(Long docId, KnowledgeDocGovernanceUpdateDTO dto) {
        if (dto == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求体不能为空");
        }
        KnowledgeDoc doc = getByIdOrThrow(docId);
        if (StringUtils.hasText(dto.getSourceTrustLevel())) {
            doc.setSourceTrustLevel(normalizeTrustLevel(dto.getSourceTrustLevel()));
        }
        if (dto.getEffectiveFrom() != null) {
            doc.setEffectiveFrom(dto.getEffectiveFrom());
        }
        if (dto.getEffectiveTo() != null) {
            doc.setEffectiveTo(dto.getEffectiveTo());
        }
        if (StringUtils.hasText(dto.getDocVersion())) {
            doc.setDocVersion(dto.getDocVersion().trim());
        }
        if (StringUtils.hasText(dto.getStatus())) {
            doc.setStatus(normalizeStatus(dto.getStatus()));
        }
        if (dto.getDisabledReason() != null) {
            doc.setDisabledReason(dto.getDisabledReason());
        }
        doc.setUpdatedTime(LocalDateTime.now());
        knowledgeDocMapper.updateById(doc);
        return toVO(doc);
    }

    private KnowledgeDocVO toVO(KnowledgeDoc knowledgeDoc) {
        KnowledgeDocVO vo = new KnowledgeDocVO();
        vo.setId(knowledgeDoc.getId());
        vo.setTitle(knowledgeDoc.getTitle());
        vo.setSourceType(KnowledgeSourceType.valueOf(knowledgeDoc.getSourceType()));
        vo.setSourceUri(knowledgeDoc.getSourceUri());
        vo.setTags(knowledgeDoc.getTags());
        vo.setSourceTrustLevel(knowledgeDoc.getSourceTrustLevel());
        vo.setEffectiveFrom(knowledgeDoc.getEffectiveFrom());
        vo.setEffectiveTo(knowledgeDoc.getEffectiveTo());
        vo.setDocVersion(knowledgeDoc.getDocVersion());
        vo.setDisabledReason(knowledgeDoc.getDisabledReason());
        vo.setStatus(KnowledgeDocStatus.valueOf(knowledgeDoc.getStatus()));
        vo.setCreatedTime(knowledgeDoc.getCreatedTime());
        vo.setFileName(knowledgeDoc.getFileName());
        vo.setMimeType(knowledgeDoc.getMimeType());
        vo.setFileHash(knowledgeDoc.getFileHash());
        vo.setParserType(knowledgeDoc.getParserType());
        vo.setParserVersion(knowledgeDoc.getParserVersion());
        vo.setChunkStrategyVersion(knowledgeDoc.getChunkStrategyVersion());
        vo.setEmbeddingModel(knowledgeDoc.getEmbeddingModel());
        vo.setActiveIndexVersion(knowledgeDoc.getActiveIndexVersion());
        return vo;
    }

    @Override
    public KnowledgeDocVO getVO(Long docId) {
        return toVO(getByIdOrThrow(docId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeDocVO> list(String keyword, KnowledgeDocStatus status,
                                     KnowledgeSourceType sourceType, Integer limit) {
        int normalizedLimit = Math.max(1, Math.min(limit == null ? 30 : limit, 100));
        LambdaQueryWrapper<KnowledgeDoc> query = new LambdaQueryWrapper<>();
        query.like(StringUtils.hasText(keyword), KnowledgeDoc::getTitle,
                        StringUtils.hasText(keyword) ? keyword.trim() : null)
                .eq(status != null, KnowledgeDoc::getStatus, status == null ? null : status.name())
                .eq(sourceType != null, KnowledgeDoc::getSourceType, sourceType == null ? null : sourceType.name())
                .orderByDesc(KnowledgeDoc::getUpdatedTime, KnowledgeDoc::getId)
                .last("LIMIT " + normalizedLimit);
        return knowledgeDocMapper.selectList(query).stream().map(this::toVO).toList();
    }

    @Override
    public KnowledgeDoc getByIdOrThrow(Long docId) {
        if (docId == null || docId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识文档ID不合法");
        }
        KnowledgeDoc knowledgeDoc = knowledgeDocMapper.selectById(docId);
        if (knowledgeDoc == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识文档不存在");
        }
        return knowledgeDoc;
    }

    private String normalizeTrustLevel(String value) {
        if (!StringUtils.hasText(value)) {
            return "MEDIUM";
        }
        String normalized = value.trim().toUpperCase();
        if ("HIGH".equals(normalized) || "MEDIUM".equals(normalized) || "LOW".equals(normalized) || "UNKNOWN".equals(normalized)) {
            return normalized;
        }
        return "UNKNOWN";
    }

    private String normalizeStatus(String value) {
        String normalized = value.trim().toUpperCase();
        try {
            return KnowledgeDocStatus.valueOf(normalized).name();
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识文档状态不合法");
        }
    }

}
