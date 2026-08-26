package org.example.online_used_marketplace.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识文档服务实现
 */
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class KnowledgeDocServiceImpl implements KnowledgeDocService {
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeIngestionProperties ingestionProperties;

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

        String normalizedContent = dto.getContent().trim();
        String contentHash = sha256(normalizedContent);
        KnowledgeDoc exactDuplicate = knowledgeDocMapper.selectOne(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getContentHash, contentHash)
                .eq(KnowledgeDoc::getSourceType, dto.getSourceType().name())
                .in(KnowledgeDoc::getStatus, List.of(
                        KnowledgeDocStatus.DRAFT.name(), KnowledgeDocStatus.ACTIVE.name()))
                .orderByDesc(KnowledgeDoc::getId)
                .last("LIMIT 1"));
        if (exactDuplicate != null) {
            return toVO(exactDuplicate);
        }

        String logicalKnowledgeId = normalizeLogicalKnowledgeId(
                dto.getLogicalKnowledgeId(), dto.getSourceType(), dto.getTitle());
        KnowledgeDoc previous = knowledgeDocMapper.selectOne(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getLogicalKnowledgeId, logicalKnowledgeId)
                .orderByDesc(KnowledgeDoc::getRevision, KnowledgeDoc::getId)
                .last("LIMIT 1"));
        int revision = previous == null || previous.getRevision() == null ? 1 : previous.getRevision() + 1;
        LocalDateTime now = LocalDateTime.now();

        KnowledgeDoc knowledgeDoc = new KnowledgeDoc();
        knowledgeDoc.setTitle(dto.getTitle().trim());
        knowledgeDoc.setContent(normalizedContent);
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
        knowledgeDoc.setLogicalKnowledgeId(logicalKnowledgeId);
        knowledgeDoc.setContentHash(contentHash);
        knowledgeDoc.setRevision(revision);
        knowledgeDoc.setSupersedesDocId(previous == null ? null : previous.getId());
        knowledgeDoc.setLifecycleManaged(true);
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
            String status = normalizeStatus(dto.getStatus());
            if (KnowledgeDocStatus.RETIRED.name().equals(status)) {
                throw new BusinessException(ResultCode.FORBIDDEN,
                        "RETIRED 仅由版本发布流程设置，不能人工修改");
            }
            if (KnowledgeDocStatus.RETIRED.name().equalsIgnoreCase(doc.getStatus())) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "保留期版本状态只能通过受控回滚重新构建索引后切换");
            }
            if (KnowledgeDocStatus.ACTIVE.name().equals(status)) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "ACTIVE 只能由三存储验证成功后的入库流程或受控回滚切换");
            }
            if (KnowledgeDocStatus.DRAFT.name().equals(status)
                    && KnowledgeDocStatus.ACTIVE.name().equals(doc.getStatus())) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "已发布文档不能退回 DRAFT，请使用 DISABLED");
            }
            if (KnowledgeDocStatus.DISABLED.name().equals(status)
                    && !StringUtils.hasText(dto.getDisabledReason())) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "禁用文档必须填写 disabledReason");
            }
            doc.setStatus(status);
            doc.setLifecycleManaged(true);
        }
        if (dto.getDisabledReason() != null) {
            doc.setDisabledReason(dto.getDisabledReason());
        }
        doc.setUpdatedTime(LocalDateTime.now());
        knowledgeDocMapper.updateById(doc);
        return toVO(doc);
    }

    @Override
    public List<Long> activateIndexedVersion(Long docId, String indexVersion) {
        if (!StringUtils.hasText(indexVersion)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "indexVersion不能为空");
        }
        KnowledgeDoc doc = getByIdOrThrow(docId);
        if (!StringUtils.hasText(doc.getLogicalKnowledgeId())) {
            KnowledgeSourceType sourceType = KnowledgeSourceType.valueOf(doc.getSourceType());
            doc.setLogicalKnowledgeId(normalizeLogicalKnowledgeId(null, sourceType, doc.getTitle()));
        }
        if (!StringUtils.hasText(doc.getContentHash())) {
            doc.setContentHash(sha256(doc.getContent()));
        }
        if (doc.getRevision() == null || doc.getRevision() <= 0) {
            doc.setRevision(1);
        }
        List<Long> retired = retireOtherActiveRevisions(doc);
        doc.setActiveIndexVersion(indexVersion.trim());
        doc.setStatus(KnowledgeDocStatus.ACTIVE.name());
        doc.setDisabledReason(null);
        doc.setLifecycleManaged(true);
        doc.setPublishedAt(LocalDateTime.now());
        doc.setRetiredAt(null);
        doc.setRetentionUntil(null);
        doc.setCleanupStatus(null);
        doc.setCleanupError(null);
        doc.setCleanedAt(null);
        doc.setUpdatedTime(LocalDateTime.now());
        if (knowledgeDocMapper.updateById(doc) <= 0) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "发布索引版本失败");
        }
        clearActiveLifecycleMarkers(doc.getId());
        return retired;
    }

    @Override
    public KnowledgeDoc reserveRetiredRevisionForRollback(Long docId) {
        KnowledgeDoc doc = getByIdOrThrow(docId);
        if (!KnowledgeDocStatus.RETIRED.name().equalsIgnoreCase(doc.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "仅可回滚处于 RETIRED 状态的知识版本");
        }
        String cleanupStatus = doc.getCleanupStatus();
        if ("RUNNING".equalsIgnoreCase(cleanupStatus)
                || "ROLLBACK_PENDING".equalsIgnoreCase(cleanupStatus)) {
            throw new BusinessException(ResultCode.CONFLICT, "目标版本正在进行生命周期操作，请稍后重试");
        }
        KnowledgeDoc patch = new KnowledgeDoc();
        patch.setCleanupStatus("ROLLBACK_PENDING");
        patch.setCleanupError(null);
        patch.setUpdatedTime(LocalDateTime.now());
        int updated = knowledgeDocMapper.update(patch, new LambdaUpdateWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getId, docId)
                .eq(KnowledgeDoc::getStatus, KnowledgeDocStatus.RETIRED.name())
                .and(wrapper -> wrapper.isNull(KnowledgeDoc::getCleanupStatus)
                        .or()
                        .in(KnowledgeDoc::getCleanupStatus, List.of("PENDING", "FAILED", "COMPLETED"))));
        if (updated <= 0) {
            throw new BusinessException(ResultCode.CONFLICT, "目标版本生命周期状态已变化，请刷新后重试");
        }
        doc.setCleanupStatus("ROLLBACK_PENDING");
        doc.setCleanupError(null);
        return doc;
    }

    @Override
    public void releaseRollbackReservation(Long docId, String reason) {
        if (docId == null || docId <= 0) {
            return;
        }
        KnowledgeDoc patch = new KnowledgeDoc();
        patch.setCleanupStatus("PENDING");
        patch.setCleanupError(safeReason(reason));
        patch.setUpdatedTime(LocalDateTime.now());
        knowledgeDocMapper.update(patch, new LambdaUpdateWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getId, docId)
                .eq(KnowledgeDoc::getStatus, KnowledgeDocStatus.RETIRED.name())
                .eq(KnowledgeDoc::getCleanupStatus, "ROLLBACK_PENDING"));
    }

    private List<Long> retireOtherActiveRevisions(KnowledgeDoc current) {
        if (!StringUtils.hasText(current.getLogicalKnowledgeId())) {
            return List.of();
        }
        List<KnowledgeDoc> active = knowledgeDocMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getLogicalKnowledgeId, current.getLogicalKnowledgeId())
                .eq(KnowledgeDoc::getStatus, KnowledgeDocStatus.ACTIVE.name())
                .ne(current.getId() != null, KnowledgeDoc::getId, current.getId()));
        List<Long> retired = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        int retentionDays = Math.max(1, ingestionProperties.getLifecycle().getRetiredRetentionDays());
        for (KnowledgeDoc previous : active) {
            previous.setStatus(KnowledgeDocStatus.RETIRED.name());
            previous.setDisabledReason("SUPERSEDED_BY:" + current.getId());
            previous.setLifecycleManaged(true);
            previous.setRetiredAt(now);
            previous.setRetentionUntil(now.plusDays(retentionDays));
            previous.setCleanupStatus("PENDING");
            previous.setCleanupError(null);
            previous.setCleanedAt(null);
            previous.setUpdatedTime(now);
            knowledgeDocMapper.updateById(previous);
            clearRetiredCleanupHistory(previous.getId());
            retired.add(previous.getId());
        }
        return retired;
    }

    private void clearActiveLifecycleMarkers(Long docId) {
        if (docId == null) {
            return;
        }
        knowledgeDocMapper.update(null, new UpdateWrapper<KnowledgeDoc>()
                .eq("id", docId)
                .eq("status", KnowledgeDocStatus.ACTIVE.name())
                .set("retired_at", null)
                .set("retention_until", null)
                .set("cleanup_status", null)
                .set("cleanup_error", null)
                .set("cleaned_at", null)
                .set("updated_time", LocalDateTime.now()));
    }

    private void clearRetiredCleanupHistory(Long docId) {
        if (docId == null) {
            return;
        }
        knowledgeDocMapper.update(null, new UpdateWrapper<KnowledgeDoc>()
                .eq("id", docId)
                .eq("status", KnowledgeDocStatus.RETIRED.name())
                .set("cleanup_error", null)
                .set("cleaned_at", null)
                .set("updated_time", LocalDateTime.now()));
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
        vo.setLogicalKnowledgeId(knowledgeDoc.getLogicalKnowledgeId());
        vo.setContentHash(knowledgeDoc.getContentHash());
        vo.setRevision(knowledgeDoc.getRevision());
        vo.setSupersedesDocId(knowledgeDoc.getSupersedesDocId());
        vo.setLifecycleManaged(knowledgeDoc.getLifecycleManaged());
        vo.setPublishedAt(knowledgeDoc.getPublishedAt());
        vo.setRetiredAt(knowledgeDoc.getRetiredAt());
        vo.setRetentionUntil(knowledgeDoc.getRetentionUntil());
        vo.setCleanupStatus(knowledgeDoc.getCleanupStatus());
        vo.setCleanupError(knowledgeDoc.getCleanupError());
        vo.setCleanedAt(knowledgeDoc.getCleanedAt());
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

    private String safeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        String normalized = reason.trim();
        return normalized.substring(0, Math.min(500, normalized.length()));
    }

    private String normalizeStatus(String value) {
        String normalized = value.trim().toUpperCase();
        try {
            return KnowledgeDocStatus.valueOf(normalized).name();
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识文档状态不合法");
        }
    }

    private String normalizeLogicalKnowledgeId(String provided,
                                               KnowledgeSourceType sourceType,
                                               String title) {
        if (StringUtils.hasText(provided)) {
            String normalized = provided.trim().toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}._:-]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (StringUtils.hasText(normalized)) {
                return normalized.substring(0, Math.min(128, normalized.length()));
            }
        }
        String normalizedTitle = title.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
        if (!StringUtils.hasText(normalizedTitle)) {
            normalizedTitle = sha256(title).substring(0, 20);
        }
        String generated = sourceType.name().toLowerCase(Locale.ROOT) + ":" + normalizedTitle;
        return generated.substring(0, Math.min(128, generated.length()));
    }

    private String sha256(String content) {
        try {
            byte[] bytes = String.valueOf(content).strip().replace("\r\n", "\n")
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "计算知识内容哈希失败");
        }
    }

}
