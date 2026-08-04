package org.example.online_used_marketplace.modules.ai.document;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DocumentSectionSupport {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern RULE_HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十百]+[章节条]|\\d+(?:\\.\\d+){0,3}[、.\\s]|[（(][一二三四五六七八九十\\d]+[）)])\\s*(.+)$");

    private DocumentSectionSupport() {
    }

    static ParsedDocument base(ParseContext context, String parserType, String text) {
        ParsedDocument out = new ParsedDocument();
        out.setFileName(context.getFileName());
        out.setMimeType(context.getMimeType());
        out.setFileHash(context.getFileHash());
        out.setParserType(parserType);
        out.setParserVersion(context.getParserVersion());
        out.setTitle(stripExtension(context.getFileName()));
        out.setNormalizedText(normalize(text, context.getMaxExtractedChars()));
        return out;
    }

    static List<ParsedSection> markdownSections(String text) {
        String normalized = normalizeLineEndings(text);
        List<ParsedSection> sections = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        int sectionStart = 0;
        int cursor = 0;
        for (String line : normalized.split("\\n", -1)) {
            Matcher matcher = MARKDOWN_HEADING.matcher(line.trim());
            if (matcher.matches()) {
                flush(sections, headingPath, content, sectionStart, cursor, null, "PARAGRAPH");
                int level = matcher.group(1).length();
                while (headingPath.size() >= level) {
                    headingPath.remove(headingPath.size() - 1);
                }
                headingPath.add(matcher.group(2).trim());
                sectionStart = cursor + line.length() + 1;
            } else {
                content.append(line).append('\n');
            }
            cursor += line.length() + 1;
        }
        flush(sections, headingPath, content, sectionStart, normalized.length(), null, "PARAGRAPH");
        return sections;
    }

    static List<ParsedSection> paragraphSections(String text, Integer page) {
        String normalized = normalizeLineEndings(text);
        List<ParsedSection> sections = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        int start = 0;
        int cursor = 0;
        for (String block : normalized.split("\\n\\s*\\n")) {
            String value = block.trim();
            if (!StringUtils.hasText(value)) {
                cursor += block.length() + 2;
                continue;
            }
            if (isHeading(value)) {
                flush(sections, headingPath, body, start, cursor, page, "PARAGRAPH");
                headingPath = new ArrayList<>(List.of(value));
                start = cursor + block.length() + 2;
            } else {
                body.append(value).append("\n\n");
            }
            cursor += block.length() + 2;
        }
        flush(sections, headingPath, body, start, normalized.length(), page, detectType(normalized));
        if (sections.isEmpty() && StringUtils.hasText(normalized)) {
            sections.add(section(List.of(), normalized, 0, normalized.length(), page, detectType(normalized)));
        }
        return sections;
    }

    static String joinSections(List<ParsedSection> sections) {
        StringBuilder out = new StringBuilder();
        for (ParsedSection section : sections) {
            if (!section.getHeadingPath().isEmpty()) {
                out.append(String.join(" > ", section.getHeadingPath())).append('\n');
            }
            out.append(section.getContent()).append("\n\n");
        }
        return out.toString().trim();
    }

    static int intMetadata(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void flush(List<ParsedSection> sections,
                              List<String> headingPath,
                              StringBuilder content,
                              int start,
                              int end,
                              Integer page,
                              String type) {
        String value = content.toString().trim();
        content.setLength(0);
        if (StringUtils.hasText(value)) {
            sections.add(section(headingPath, value, start, end, page, type));
        }
    }

    private static ParsedSection section(List<String> headings,
                                         String content,
                                         int start,
                                         int end,
                                         Integer page,
                                         String type) {
        ParsedSection section = new ParsedSection();
        section.setSectionId("s-" + String.format(Locale.ROOT, "%04d", Math.max(1, start + 1)));
        section.setHeadingPath(new ArrayList<>(headings));
        section.setPageFrom(page);
        section.setPageTo(page);
        section.setContentType(type);
        section.setContent(content.trim());
        section.setSourceStart(Math.max(0, start));
        section.setSourceEnd(Math.max(start, end));
        return section;
    }

    private static boolean isHeading(String value) {
        return value.length() <= 100
                && !value.contains("\n")
                && (RULE_HEADING.matcher(value).matches()
                || value.endsWith("：")
                || value.endsWith(":"));
    }

    private static String detectType(String value) {
        String trimmed = value.trim();
        if (trimmed.matches("(?s).*(^|\\n)\\s*[-*+]\\s+.+")) {
            return "LIST";
        }
        if (trimmed.contains("\t") || trimmed.matches("(?s).*\\|.+\\|.*")) {
            return "TABLE";
        }
        return "PARAGRAPH";
    }

    private static String normalize(String text, int maxChars) {
        String normalized = normalizeLineEndings(text)
                .replace("\u0000", "")
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (normalized.length() > maxChars) {
            throw new IllegalArgumentException("extracted text exceeds limit: " + maxChars);
        }
        return normalized;
    }

    private static String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String stripExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "untitled";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
