package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * ES 中的知识切片文档结构。
 */
@Data
@Document(indexName = "ai_knowledge_chunk")
public class KnowledgeChunkEsDoc {

    @Id
    private String id;

    private Long chunkId;
    private Long docId;

    @Field(type = FieldType.Text)
    private String content;

    @Field(type = FieldType.Text)
    private String docTitle;

    @Field(type = FieldType.Keyword)
    private String sourceType;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String riskLevel;

    @Field(type = FieldType.Text)
    private String sourceUri;

    @Field(type = FieldType.Keyword)
    private String sourceTrustLevel;

    @Field(type = FieldType.Keyword)
    private String docVersion;

    @Field(type = FieldType.Keyword)
    private String effectiveFrom;

    @Field(type = FieldType.Keyword)
    private String effectiveTo;

    @Field(type = FieldType.Keyword)
    private String docStatus;

    @Field(type = FieldType.Text)
    private String disabledReason;

    @Field(type = FieldType.Keyword)
    private String indexVersion;
}
