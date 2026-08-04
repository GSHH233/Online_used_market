package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.common.BusinessException;
import org.example.online_used_marketplace.common.ResultCode;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.example.online_used_marketplace.modules.ai.document.ParsedDocument;
import org.example.online_used_marketplace.modules.ai.document.ParsedSection;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.service.ChunkingService;
import org.example.online_used_marketplace.modules.ai.service.SemanticBoundaryService;
import org.example.online_used_marketplace.modules.ai.service.TokenCounterService;
import org.example.online_used_marketplace.modules.ai.vo.ChunkPayloadVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ChunkingServiceImpl implements ChunkingService {

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？!?；;])|\\n+");

    private final ObjectMapper objectMapper;
    private final TokenCounterService tokenCounter;
    private final SemanticBoundaryService semanticBoundaryService;
    private final KnowledgeIngestionProperties properties;

    @Override
    public List<ChunkPayloadVO> split(Long docId, String content, int chunkSize, int overlap) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(docId);
        doc.setContent(content);
        return split(doc, chunkSize, overlap);
    }

    @Override
    public List<ChunkPayloadVO> split(KnowledgeDoc doc, int ignoredChunkSize, int ignoredOverlap) {
        validate(doc);
        int maxTokens = properties.getChunking().getMaxTokens();
        int targetTokens = Math.min(properties.getChunking().getTargetTokens(), maxTokens);
        int minTokens = Math.min(properties.getChunking().getMinTokens(), targetTokens);
        int overlapTokens = Math.min(properties.getChunking().getOverlapTokens(), Math.max(0, maxTokens - 1));

        List<ParsedSection> sections = loadSections(doc);
        List<ChunkPayloadVO> candidates = new ArrayList<>();
        for (ParsedSection section : sections) {
            List<String> paragraphs = paragraphs(section.getContent());
            List<SemanticBoundaryService.SemanticGroup> groups =
                    semanticBoundaryService.split(section, paragraphs);
            if (groups.isEmpty()) {
                groups = deterministicGroups(paragraphs, targetTokens);
            }
            for (SemanticBoundaryService.SemanticGroup group : groups) {
                String text = String.join("\n\n",
                        paragraphs.subList(group.startInclusive(), group.endInclusive() + 1));
                candidates.addAll(splitOversize(section, text, group.topic(), maxTokens, overlapTokens));
            }
        }
        List<ChunkPayloadVO> merged = mergeShort(candidates, minTokens, maxTokens);
        for (int i = 0; i < merged.size(); i++) {
            ChunkPayloadVO chunk = merged.get(i);
            chunk.setChunkIndex(i);
            chunk.setTokenCount(tokenCounter.count(chunk.getContent()));
            chunk.setMetadata("{\"docId\":" + doc.getId() + ",\"chunkIndex\":" + i + "}");
        }
        if (merged.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文档切片结果为空");
        }
        return merged;
    }

    private List<ParsedSection> loadSections(KnowledgeDoc doc) {
        if (StringUtils.hasText(doc.getStructureJson())) {
            try {
                ParsedDocument parsed = objectMapper.readValue(doc.getStructureJson(), ParsedDocument.class);
                if (parsed.getSections() != null && !parsed.getSections().isEmpty()) {
                    return parsed.getSections();
                }
            } catch (Exception ignored) {
                // Fall through to the deterministic legacy structure.
            }
        }
        ParsedSection section = new ParsedSection();
        section.setSectionId("legacy-1");
        section.setContent(doc.getContent().trim());
        section.setSourceStart(0);
        section.setSourceEnd(doc.getContent().length());
        return List.of(section);
    }

    private List<SemanticBoundaryService.SemanticGroup> deterministicGroups(List<String> paragraphs, int targetTokens) {
        List<SemanticBoundaryService.SemanticGroup> groups = new ArrayList<>();
        int start = 0;
        int tokens = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            int next = tokenCounter.count(paragraphs.get(i));
            if (i > start && tokens + next > targetTokens) {
                groups.add(new SemanticBoundaryService.SemanticGroup(start, i - 1, ""));
                start = i;
                tokens = 0;
            }
            tokens += next;
        }
        if (start < paragraphs.size()) {
            groups.add(new SemanticBoundaryService.SemanticGroup(start, paragraphs.size() - 1, ""));
        }
        return groups;
    }

    private List<ChunkPayloadVO> splitOversize(ParsedSection section,
                                                String text,
                                                String topic,
                                                int maxTokens,
                                                int overlapTokens) {
        if (tokenCounter.count(text) <= maxTokens) {
            return List.of(payload(section, text, topic, 0));
        }
        List<String> sentences = new ArrayList<>();
        for (String value : SENTENCE_BOUNDARY.split(text)) {
            if (StringUtils.hasText(value)) {
                sentences.add(value.trim());
            }
        }
        List<ChunkPayloadVO> out = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentOverlapChars = 0;
        for (String sentence : sentences) {
            if (tokenCounter.count(sentence) > maxTokens) {
                if (!current.isEmpty()) {
                    out.add(payload(section, String.join("", current), topic, currentOverlapChars));
                    current.clear();
                    currentOverlapChars = 0;
                }
                out.addAll(splitAtomic(section, sentence, topic, maxTokens, overlapTokens));
                continue;
            }
            String combined = String.join("", current) + sentence;
            if (!current.isEmpty() && tokenCounter.count(combined) > maxTokens) {
                String completed = String.join("", current);
                out.add(payload(section, completed, topic, currentOverlapChars));
                String overlap = tailWithinTokens(completed, overlapTokens);
                current.clear();
                currentOverlapChars = 0;
                if (StringUtils.hasText(overlap)) {
                    current.add(overlap);
                    currentOverlapChars = overlap.length();
                }
            }
            current.add(sentence);
        }
        if (!current.isEmpty()) {
            out.add(payload(section, String.join("", current), topic, currentOverlapChars));
        }
        return out;
    }

    private List<ChunkPayloadVO> splitAtomic(ParsedSection section,
                                             String text,
                                             String topic,
                                             int maxTokens,
                                             int overlapTokens) {
        List<ChunkPayloadVO> out = new ArrayList<>();
        int start = 0;
        int leadingOverlapChars = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + Math.max(1, maxTokens));
            while (end > start + 1 && tokenCounter.count(text.substring(start, end)) > maxTokens) {
                end--;
            }
            String piece = text.substring(start, end);
            out.add(payload(section, piece, topic, leadingOverlapChars));
            if (end >= text.length()) {
                break;
            }
            String overlap = tailWithinTokens(piece, overlapTokens);
            int nextStart = Math.max(start + 1, end - overlap.length());
            leadingOverlapChars = end - nextStart;
            start = nextStart;
        }
        return out;
    }

    private List<ChunkPayloadVO> mergeShort(List<ChunkPayloadVO> chunks, int minTokens, int maxTokens) {
        List<ChunkPayloadVO> out = new ArrayList<>();
        for (ChunkPayloadVO current : chunks) {
            int currentTokens = tokenCounter.count(current.getContent());
            if (currentTokens < minTokens && !out.isEmpty()) {
                ChunkPayloadVO previous = out.get(out.size() - 1);
                String currentContent = current.getContent();
                if (Boolean.TRUE.equals(current.getOverlap())) {
                    int shared = current.getOverlapChars() == null ? 0 : current.getOverlapChars();
                    currentContent = currentContent.substring(Math.min(shared, currentContent.length()));
                }
                String merged = StringUtils.hasText(currentContent)
                        ? previous.getContent() + "\n\n" + currentContent
                        : previous.getContent();
                if (compatible(previous, current) && tokenCounter.count(merged) <= maxTokens) {
                    previous.setContent(merged);
                    previous.setSourceEnd(current.getSourceEnd());
                    previous.setPageTo(current.getPageTo());
                    previous.setTokenCount(tokenCounter.count(merged));
                    continue;
                }
            }
            out.add(current);
        }
        return out;
    }

    private boolean compatible(ChunkPayloadVO left, ChunkPayloadVO right) {
        return Objects.equals(left.getHeadingPath(), right.getHeadingPath())
                && Objects.equals(left.getContentType(), right.getContentType())
                && !"TABLE".equalsIgnoreCase(left.getContentType())
                && !"WARNING".equalsIgnoreCase(left.getContentType());
    }

    private ChunkPayloadVO payload(ParsedSection section, String content, String topic, int overlapChars) {
        ChunkPayloadVO out = new ChunkPayloadVO();
        out.setContent(content.trim());
        out.setTokenCount(tokenCounter.count(out.getContent()));
        out.setHeadingPath(new ArrayList<>(section.getHeadingPath()));
        out.setPageFrom(section.getPageFrom());
        out.setPageTo(section.getPageTo());
        out.setContentType(section.getContentType());
        out.setSourceStart(section.getSourceStart());
        out.setSourceEnd(section.getSourceEnd());
        out.setSemanticTopic(topic);
        out.setOverlap(overlapChars > 0);
        out.setOverlapChars(Math.max(0, overlapChars));
        return out;
    }

    private List<String> paragraphs(String content) {
        List<String> out = new ArrayList<>();
        for (String value : content.replace("\r\n", "\n").split("\\n\\s*\\n|(?<=。)\\n")) {
            if (StringUtils.hasText(value)) {
                out.add(value.trim());
            }
        }
        if (out.isEmpty() && StringUtils.hasText(content)) {
            out.add(content.trim());
        }
        return out;
    }

    private String tailWithinTokens(String text, int tokenLimit) {
        if (tokenLimit <= 0 || !StringUtils.hasText(text)) {
            return "";
        }
        int start = text.length();
        while (start > 0 && tokenCounter.count(text.substring(start - 1)) <= tokenLimit) {
            start--;
        }
        return text.substring(start);
    }

    private void validate(KnowledgeDoc doc) {
        if (doc == null || doc.getId() == null || doc.getId() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "知识文档ID不合法");
        }
        if (!StringUtils.hasText(doc.getContent())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文档内容不存在");
        }
    }
}
