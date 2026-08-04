package org.example.online_used_marketplace.modules.ai.document;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ParsedDocument {
    private String fileName;
    private String mimeType;
    private String fileHash;
    private String parserType;
    private String parserVersion;
    private String title;
    private String normalizedText;
    private List<ParsedSection> sections = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
}
