package org.example.online_used_marketplace.modules.ai.document;

import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class MarkdownKnowledgeDocumentParser implements KnowledgeDocumentParser {

    @Override
    public boolean supports(String mimeType, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown")
                || "text/markdown".equalsIgnoreCase(mimeType);
    }

    @Override
    public ParsedDocument parse(Resource resource, ParseContext context) {
        try {
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(true)
                    .withIncludeCodeBlock(false)
                    .withIncludeBlockquote(true)
                    .withAdditionalMetadata("filename", context.getFileName())
                    .build();
            new MarkdownDocumentReader(resource, config).read();
            String raw = resource.getContentAsString(StandardCharsets.UTF_8);
            ParsedDocument out = DocumentSectionSupport.base(context, "SPRING_AI_MARKDOWN", raw);
            out.setSections(DocumentSectionSupport.markdownSections(out.getNormalizedText()));
            out.setNormalizedText(DocumentSectionSupport.joinSections(out.getSections()));
            return out;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Markdown parsing failed: " + ex.getMessage(), ex);
        }
    }
}
