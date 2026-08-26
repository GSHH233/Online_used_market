package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.enums.KnowledgeSourceType;
import org.example.online_used_marketplace.modules.ai.vo.KnowledgeDocumentUploadVO;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeDocumentUploadService {
    KnowledgeDocumentUploadVO upload(MultipartFile file,
                                     String title,
                                     KnowledgeSourceType sourceType,
                                     String tags,
                                     String sourceTrustLevel,
                                     String docVersion,
                                     String logicalKnowledgeId,
                                     boolean async);
}
