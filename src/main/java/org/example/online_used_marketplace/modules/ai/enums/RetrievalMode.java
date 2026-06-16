package org.example.online_used_marketplace.modules.ai.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 知识检索模式。
 */
public enum RetrievalMode {
    VECTOR_ONLY("vector-only"),
    KEYWORD_ONLY("keyword-only"),
    HYBRID("hybrid");

    private final String code;

    RetrievalMode(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static RetrievalMode from(String value) {
        if (value == null || value.isBlank()) {
            return HYBRID;
        }
        String normalized = value.trim().replace("-", "_").toUpperCase();
        for (RetrievalMode mode : values()) {
            if (mode.name().equals(normalized) || mode.code.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported retrieval mode: " + value);
    }
}
