package org.example.online_used_marketplace.modules.ai.document;

import org.springframework.core.io.Resource;

public interface KnowledgeDocumentParser {
    boolean supports(String mimeType, String fileName);
    ParsedDocument parse(Resource resource, ParseContext context);
}
