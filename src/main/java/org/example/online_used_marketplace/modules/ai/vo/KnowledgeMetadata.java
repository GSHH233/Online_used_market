package org.example.online_used_marketplace.modules.ai.vo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 知识切片元信息（统一 schema）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeMetadata {

    public static final String UNKNOWN = "UNKNOWN";

    private Long docId;
    private Integer chunkIndex;
    private String sourceType;
    private String category;
    private String riskLevel;
    private String docTitle;
    private String sourceUri;
    private List<String> tags;
    private String sourceTrustLevel;
    private String docVersion;
    private String effectiveFrom;
    private String effectiveTo;
    private String docStatus;
    private String disabledReason;

    public void normalize() {
        sourceType = normalizeText(sourceType, UNKNOWN);
        category = normalizeText(category, UNKNOWN);
        riskLevel = normalizeText(riskLevel, UNKNOWN);
        docTitle = normalizeText(docTitle, "");
        sourceUri = normalizeText(sourceUri, "");
        sourceTrustLevel = normalizeText(sourceTrustLevel, "MEDIUM");
        docVersion = normalizeText(docVersion, "v1");
        effectiveFrom = normalizeText(effectiveFrom, "");
        effectiveTo = normalizeText(effectiveTo, "");
        docStatus = normalizeText(docStatus, "");
        disabledReason = normalizeText(disabledReason, "");
        tags = (tags == null) ? Collections.emptyList() : tags;
    }

    public static KnowledgeMetadata fromJson(String metadataJson, ObjectMapper objectMapper) {
        if (!StringUtils.hasText(metadataJson)) {
            return null;
        }
        try {
            KnowledgeMetadata metadata = objectMapper.readValue(metadataJson, KnowledgeMetadata.class);
            if (metadata != null) {
                metadata.normalize();
            }
            return metadata;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("metadata JSON解析失败", e);
        }
    }

    public String toJson(ObjectMapper objectMapper) {
        try {
            normalize();
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("metadata JSON序列化失败", e);
        }
    }

    public static KnowledgeMetadata of(
            Long docId,
            Integer chunkIndex,
            String sourceType,
            String category,
            String riskLevel,
            String docTitle,
            String sourceUri,
            List<String> tags
    ) {
        KnowledgeMetadata metadata = KnowledgeMetadata.builder()
                .docId(docId)
                .chunkIndex(chunkIndex)
                .sourceType(sourceType)
                .category(category)
                .riskLevel(riskLevel)
                .docTitle(docTitle)
                .sourceUri(sourceUri)
                .tags(tags)
                .build();
        metadata.normalize();
        return metadata;
    }

    private static String normalizeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
