package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.online_used_marketplace.modules.ai.document.ParsedDocument;
import org.example.online_used_marketplace.modules.ai.document.ParsedSection;
import org.example.online_used_marketplace.modules.ai.entity.KnowledgeDoc;
import org.example.online_used_marketplace.modules.ai.service.ChunkQualityValidator;
import org.example.online_used_marketplace.modules.ai.service.TokenCounterService;
import org.example.online_used_marketplace.modules.ai.vo.ChunkPayloadVO;
import org.example.online_used_marketplace.modules.ai.vo.ChunkQualityReportVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;

@Service
public class ChunkQualityValidatorImpl implements ChunkQualityValidator {

    private final TokenCounterService tokenCounterService;
    private final ObjectMapper objectMapper;

    public ChunkQualityValidatorImpl(TokenCounterService tokenCounterService, ObjectMapper objectMapper) {
        this.tokenCounterService = tokenCounterService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChunkQualityReportVO validate(KnowledgeDoc doc,
                                         List<ChunkPayloadVO> chunks,
                                         int minTokens,
                                         int maxTokens) {
        List<ChunkPayloadVO> values = chunks == null ? Collections.emptyList() : chunks;
        ChunkQualityReportVO report = new ChunkQualityReportVO();
        report.setChunkCount(values.size());
        List<Integer> tokenCounts = new ArrayList<>();
        int heading = 0;
        int page = 0;
        int sourcePosition = 0;
        for (ChunkPayloadVO chunk : values) {
            if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
                report.setEmptyCount(report.getEmptyCount() + 1);
                continue;
            }
            int tokens = tokenCounterService.count(chunk.getContent());
            tokenCounts.add(tokens);
            if (tokens < minTokens) {
                report.setShortCount(report.getShortCount() + 1);
            }
            if (tokens > maxTokens) {
                report.setOversizeCount(report.getOversizeCount() + 1);
            }
            if (Boolean.TRUE.equals(chunk.getOverlap())) {
                report.setOverlapCount(report.getOverlapCount() + 1);
            }
            if (chunk.getHeadingPath() != null && !chunk.getHeadingPath().isEmpty()) {
                heading++;
            }
            if (chunk.getPageFrom() != null) {
                page++;
            }
            if (chunk.getSourceStart() != null && chunk.getSourceEnd() != null
                    && chunk.getSourceStart() >= 0 && chunk.getSourceEnd() >= chunk.getSourceStart()) {
                sourcePosition++;
            }
            chunk.setTokenCount(tokens);
        }
        tokenCounts.sort(Integer::compareTo);
        report.setTokenP50(percentile(tokenCounts, 0.50));
        report.setTokenP95(percentile(tokenCounts, 0.95));
        report.setTokenMax(tokenCounts.isEmpty() ? 0 : tokenCounts.get(tokenCounts.size() - 1));
        int nonEmpty = tokenCounts.size();
        report.setHeadingCoverage(ratio(heading, nonEmpty));
        report.setPageCoverage(ratio(page, nonEmpty));
        report.setSourcePositionCoverage(ratio(sourcePosition, nonEmpty));
        report.setSourceTextCoverage(sourceTextCoverage(doc, values));
        report.setAbnormalDuplicateRate(abnormalDuplicateRate(doc, values));
        if (values.isEmpty()) {
            report.getViolations().add("NO_CHUNKS");
        }
        if (report.getEmptyCount() > 0) {
            report.getViolations().add("EMPTY_CHUNK");
        }
        if (report.getOversizeCount() > 0) {
            report.getViolations().add("TOKEN_LIMIT_EXCEEDED");
        }
        if (report.getSourceTextCoverage() < 1D) {
            report.getViolations().add("SOURCE_TEXT_NOT_FULLY_COVERED");
        }
        if (report.getAbnormalDuplicateRate() > 0D) {
            report.getViolations().add("ABNORMAL_NON_OVERLAP_DUPLICATE");
        }
        report.setValid(report.getViolations().isEmpty());
        return report;
    }

    private int percentile(List<Integer> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0D : numerator * 1D / denominator;
    }

    private double sourceTextCoverage(KnowledgeDoc doc, List<ChunkPayloadVO> chunks) {
        List<ParsedSection> sections = parsedSections(doc);
        if (sections.isEmpty()) {
            return chunks.isEmpty() ? 0D : 1D;
        }
        int covered = 0;
        for (ParsedSection section : sections) {
            List<ChunkPayloadVO> candidates = chunks.stream()
                    .filter(chunk -> Objects.equals(chunk.getHeadingPath(), section.getHeadingPath()))
                    .toList();
            String reconstructed = reconstruct(candidates);
            if (normalize(reconstructed).contains(normalize(section.getContent()))) {
                covered++;
            }
        }
        return ratio(covered, sections.size());
    }

    private List<ParsedSection> parsedSections(KnowledgeDoc doc) {
        if (doc == null || !StringUtils.hasText(doc.getStructureJson())) {
            return List.of();
        }
        try {
            ParsedDocument parsed = objectMapper.readValue(doc.getStructureJson(), ParsedDocument.class);
            return parsed.getSections() == null ? List.of() : parsed.getSections();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String reconstruct(List<ChunkPayloadVO> chunks) {
        StringBuilder out = new StringBuilder();
        String previous = "";
        for (ChunkPayloadVO chunk : chunks) {
            String raw = chunk == null || chunk.getContent() == null ? "" : chunk.getContent();
            String current = normalize(raw);
            if (Boolean.TRUE.equals(chunk == null ? false : chunk.getOverlap())) {
                if (chunk.getOverlapChars() != null && chunk.getOverlapChars() > 0) {
                    int chars = Math.min(chunk.getOverlapChars(), raw.length());
                    out.append(normalize(raw.substring(chars)));
                } else {
                    int overlap = commonSuffixPrefix(previous, current);
                    out.append(current.substring(Math.min(overlap, current.length())));
                }
            } else {
                out.append(current);
            }
            previous = current;
        }
        return out.toString();
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

    private double abnormalDuplicateRate(KnowledgeDoc doc, List<ChunkPayloadVO> chunks) {
        Set<String> seen = new HashSet<>();
        int duplicate = 0;
        int checked = 0;
        String source = normalize(doc == null ? "" : doc.getContent());
        for (ChunkPayloadVO chunk : chunks) {
            if (chunk == null || Boolean.TRUE.equals(chunk.getOverlap())
                    || !StringUtils.hasText(chunk.getContent())) {
                continue;
            }
            checked++;
            String normalizedChunk = normalize(chunk.getContent());
            if (!seen.add(normalizedChunk) && occurrences(source, normalizedChunk) < 2) {
                duplicate++;
            }
        }
        return ratio(duplicate, checked);
    }

    private int occurrences(String source, String target) {
        if (target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(target, cursor)) >= 0) {
            count++;
            cursor += target.length();
        }
        return count;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}
