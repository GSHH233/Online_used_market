package org.example.online_used_marketplace.modules.ai.document;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PdfKnowledgeDocumentParser implements KnowledgeDocumentParser {

    @Override
    public boolean supports(String mimeType, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(mimeType);
    }

    @Override
    public ParsedDocument parse(Resource resource, ParseContext context) {
        try {
            PagePdfDocumentReader reader = new PagePdfDocumentReader(resource,
                    PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build());
            List<Document> pages = reader.read();
            if (pages.size() > context.getMaxPdfPages()) {
                throw new IllegalArgumentException(
                        "PDF page count exceeds limit: " + pages.size() + " > " + context.getMaxPdfPages());
            }
            List<ParsedSection> sections = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();
            int pageFallback = 1;
            for (Document page : pages) {
                String text = page.getText();
                if (text == null || text.isBlank()) {
                    pageFallback++;
                    continue;
                }
                Map<String, Object> metadata = page.getMetadata();
                int pageNumber = pageNumber(metadata, pageFallback++);
                List<ParsedSection> pageSections = DocumentSectionSupport.paragraphSections(text, pageNumber);
                sections.addAll(pageSections);
                fullText.append(text).append("\n\n");
            }
            ParsedDocument out = DocumentSectionSupport.base(context, "SPRING_AI_PDF_PAGE", fullText.toString());
            out.setSections(sections);
            if (sections.isEmpty()) {
                out.getWarnings().add("UNSUPPORTED_OCR_REQUIRED");
                throw new IllegalArgumentException("PDF contains no extractable text; OCR is required");
            }
            out.setNormalizedText(DocumentSectionSupport.joinSections(sections));
            return out;
        } catch (Exception ex) {
            throw new IllegalArgumentException("PDF parsing failed: " + ex.getMessage(), ex);
        }
    }

    private int pageNumber(Map<String, Object> metadata, int fallback) {
        for (String key : List.of("page_number", "pageNumber", "page", "pageIndex")) {
            if (metadata.containsKey(key)) {
                return DocumentSectionSupport.intMetadata(metadata.get(key), fallback);
            }
        }
        return fallback;
    }
}
