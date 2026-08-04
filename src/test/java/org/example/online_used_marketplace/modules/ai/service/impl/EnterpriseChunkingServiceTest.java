package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.document.ParsedDocument;
import org.example.online_used_marketplace.modules.ai.document.ParsedSection;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.service.SemanticBoundaryService;
import org.example.online_used_marketplace.modules.ai.service.TokenCounterService;
import org.example.online_used_marketplace.modules.ai.vo.ChunkPayloadVO;
import org.example.online_used_marketplace.modules.ai.vo.ChunkQualityReportVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterpriseChunkingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TokenCounterService tokenCounter = new Cl100kTokenCounterService();

    @Test
    void splitsOversizeTextByRealTokenLimitAndKeepsHeadingMetadata() throws Exception {
        KnowledgeIngestionProperties properties = properties(80, 60, 12, 8);
        SemanticBoundaryService fallback = (section, paragraphs) -> List.of();
        ChunkingServiceImpl service = new ChunkingServiceImpl(
                objectMapper, tokenCounter, fallback, properties);
        KnowledgeDoc doc = structuredDoc(
                "发布规则",
                "手机类目",
                ("无发票商品必须如实披露来源和购买凭证，不能使用全新官方制造误导。"
                        + "未拆封只代表包装状态，不能替代来源证明。").repeat(20));

        List<ChunkPayloadVO> chunks = service.split(doc, 600, 100);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getTokenCount() <= 80));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getHeadingPath().equals(List.of("手机类目"))));
        assertEquals(
                chunks.stream().mapToInt(chunk -> tokenCounter.count(chunk.getContent())).boxed().toList(),
                chunks.stream().map(ChunkPayloadVO::getTokenCount).toList());
    }

    @Test
    void mergesCompatibleShortParagraphsWithoutCrossingTokenLimit() throws Exception {
        KnowledgeIngestionProperties properties = properties(100, 80, 30, 5);
        SemanticBoundaryService oneParagraphPerGroup = (section, paragraphs) ->
                java.util.stream.IntStream.range(0, paragraphs.size())
                        .mapToObj(index -> new SemanticBoundaryService.SemanticGroup(index, index, "rule"))
                        .toList();
        ChunkingServiceImpl service = new ChunkingServiceImpl(
                objectMapper, tokenCounter, oneParagraphPerGroup, properties);
        KnowledgeDoc doc = structuredDoc(
                "短规则",
                "同一章节",
                "规则一：如实描述。\n\n规则二：保留证据。\n\n规则三：不得引流。");

        List<ChunkPayloadVO> chunks = service.split(doc, 600, 100);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("规则一"));
        assertTrue(chunks.get(0).getContent().contains("规则三"));
        assertTrue(chunks.get(0).getTokenCount() <= 100);
    }

    @Test
    void qualityGateRejectsEmptyAndOversizeChunks() {
        ChunkQualityValidatorImpl validator = new ChunkQualityValidatorImpl(tokenCounter, objectMapper);
        ChunkPayloadVO empty = new ChunkPayloadVO();
        empty.setContent(" ");
        ChunkPayloadVO oversize = new ChunkPayloadVO();
        oversize.setContent("发布风险证据".repeat(100));

        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(1L);
        doc.setContent("发布风险证据");
        ChunkQualityReportVO report = validator.validate(doc, List.of(empty, oversize), 10, 20);

        assertFalse(report.isValid());
        assertEquals(1, report.getEmptyCount());
        assertEquals(1, report.getOversizeCount());
        assertTrue(report.getViolations().contains("EMPTY_CHUNK"));
        assertTrue(report.getViolations().contains("TOKEN_LIMIT_EXCEEDED"));
    }

    @Test
    void longRepeatedTextKeepsFullSourceCoverageAfterOverlapSplitting() throws Exception {
        KnowledgeIngestionProperties properties = properties(600, 450, 100, 80);
        SemanticBoundaryService fallback = (section, paragraphs) -> List.of();
        ChunkingServiceImpl service = new ChunkingServiceImpl(
                objectMapper, tokenCounter, fallback, properties);
        String unit = "本段用于验证超长内容会在句子边界拆分，同时保留必要重叠并保证每个普通正文切片"
                + "不超过配置的最大Token数量。发布者必须如实披露影响买家判断的信息。 ";
        KnowledgeDoc doc = structuredDoc("超长规则拆分", "", unit.repeat(160));

        List<ChunkPayloadVO> chunks = service.split(doc, 600, 100);
        ChunkQualityReportVO report = new ChunkQualityValidatorImpl(tokenCounter, objectMapper)
                .validate(doc, chunks, 100, 600);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getTokenCount() <= 600));
        assertTrue(report.isValid(), () -> "violations=" + report.getViolations()
                + ", sourceCoverage=" + report.getSourceTextCoverage()
                + ", " + coverageDiagnostics(doc.getContent(), chunks));
    }

    private String coverageDiagnostics(String source, List<ChunkPayloadVO> chunks) {
        String normalizedSource = source.replaceAll("\\s+", "");
        StringBuilder reconstructed = new StringBuilder();
        String previous = "";
        for (ChunkPayloadVO chunk : chunks) {
            String raw = chunk.getContent();
            String current = raw.replaceAll("\\s+", "");
            if (Boolean.TRUE.equals(chunk.getOverlap())
                    && chunk.getOverlapChars() != null && chunk.getOverlapChars() > 0) {
                int chars = Math.min(chunk.getOverlapChars(), raw.length());
                reconstructed.append(raw.substring(chars).replaceAll("\\s+", ""));
            } else {
                int overlap = Boolean.TRUE.equals(chunk.getOverlap())
                        ? commonSuffixPrefix(previous, current)
                        : 0;
                reconstructed.append(current.substring(Math.min(overlap, current.length())));
            }
            previous = current;
        }
        String value = reconstructed.toString();
        int mismatch = 0;
        while (mismatch < normalizedSource.length() && mismatch < value.length()
                && normalizedSource.charAt(mismatch) == value.charAt(mismatch)) {
            mismatch++;
        }
        return "sourceLength=" + normalizedSource.length()
                + ", reconstructedLength=" + value.length()
                + ", mismatch=" + mismatch
                + ", overlapChunks=" + chunks.stream().filter(ChunkPayloadVO::getOverlap).count();
    }

    private int commonSuffixPrefix(String previous, String current) {
        int limit = Math.min(previous.length(), current.length());
        for (int size = limit; size > 0; size--) {
            if (previous.regionMatches(previous.length() - size, current, 0, size)) {
                return size;
            }
        }
        return 0;
    }

    private KnowledgeIngestionProperties properties(int max, int target, int min, int overlap) {
        KnowledgeIngestionProperties properties = new KnowledgeIngestionProperties();
        properties.getChunking().setSemanticEnabled(false);
        properties.getChunking().setMaxTokens(max);
        properties.getChunking().setTargetTokens(target);
        properties.getChunking().setMinTokens(min);
        properties.getChunking().setOverlapTokens(overlap);
        return properties;
    }

    private KnowledgeDoc structuredDoc(String title, String heading, String content) throws Exception {
        ParsedSection section = new ParsedSection();
        section.setSectionId("s-1");
        section.setHeadingPath(List.of(heading));
        section.setContentType("PARAGRAPH");
        section.setContent(content);
        section.setSourceStart(0);
        section.setSourceEnd(content.length());
        ParsedDocument parsed = new ParsedDocument();
        parsed.setTitle(title);
        parsed.setNormalizedText(content);
        parsed.setSections(List.of(section));

        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(999L);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setStructureJson(objectMapper.writeValueAsString(parsed));
        return doc;
    }
}
