package org.example.online_used_marketplace.modules.ai.document;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ParseContext {
    String fileName;
    String mimeType;
    String fileHash;
    String parserVersion;
    int maxExtractedChars;
    int maxPdfPages;
}
