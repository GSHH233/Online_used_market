package org.example.online_used_marketplace.modules.ai.document;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeDocumentParserTest {

    @Test
    void parsesExtractableChinesePdfWithPageMetadata() {
        Path fixture = Path.of("eval", "fixtures", "knowledge-docs", "ing-pdf-001.pdf");
        ParsedDocument parsed = new PdfKnowledgeDocumentParser().parse(
                new FileSystemResource(fixture), context(fixture));

        assertTrue(parsed.getNormalizedText().contains("发票缺失"));
        assertTrue(parsed.getNormalizedText().contains("激活状态"));
        assertFalse(parsed.getSections().isEmpty());
        assertTrue(parsed.getSections().stream().allMatch(section -> section.getPageFrom() != null));
    }

    @Test
    void rejectsPlainTextDisguisedAsPdf() {
        Path fixture = Path.of("eval", "fixtures", "knowledge-docs", "ing-invalid-001.pdf");
        assertThrows(IllegalArgumentException.class, () -> new PdfKnowledgeDocumentParser().parse(
                new FileSystemResource(fixture), context(fixture)));
    }

    private ParseContext context(Path fixture) {
        return ParseContext.builder()
                .fileName(fixture.getFileName().toString())
                .mimeType("application/pdf")
                .fileHash("test")
                .parserVersion("test")
                .maxExtractedChars(100_000)
                .maxPdfPages(10)
                .build();
    }
}
