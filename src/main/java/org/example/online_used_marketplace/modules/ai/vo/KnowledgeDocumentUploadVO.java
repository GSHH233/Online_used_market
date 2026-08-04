package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KnowledgeDocumentUploadVO {
    private KnowledgeDocVO document;
    private KnowledgeIndexTaskVO task;
    private String fileName;
    private String mimeType;
    private String fileHash;
    private String parserType;
    private Integer sectionCount;
    private List<String> warnings = new ArrayList<>();
}
