package org.example.online_used_marketplace.modules.ai.document;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class WordKnowledgeDocumentParser implements KnowledgeDocumentParser {

    @Override
    public boolean supports(String mimeType, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".doc") || name.endsWith(".docx")
                || (mimeType != null && (mimeType.contains("msword")
                || mimeType.contains("wordprocessingml")));
    }

    @Override
    public ParsedDocument parse(Resource resource, ParseContext context) {
        try {
            List<Document> documents = new TikaDocumentReader(resource).read();
            StringBuilder fullText = new StringBuilder();
            for (Document document : documents) {
                if (document.getText() != null) {
                    fullText.append(document.getText()).append("\n\n");
                }
            }
            ParsedDocument out = DocumentSectionSupport.base(context, "SPRING_AI_TIKA_WORD", fullText.toString());
            out.setSections(DocumentSectionSupport.paragraphSections(out.getNormalizedText(), null));
            if (out.getSections().isEmpty()) {
                throw new IllegalArgumentException("Word document contains no extractable text");
            }
            out.setNormalizedText(DocumentSectionSupport.joinSections(out.getSections()));
            if (out.getNormalizedText().contains("\t")) {
                out.getWarnings().add("WORD_TABLE_STRUCTURE_MAY_BE_LOSSY");
            }
            return out;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Word parsing failed: " + ex.getMessage(), ex);
        }
    }
}
