package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.document.ParsedSection;

import java.util.List;

public interface SemanticBoundaryService {
    List<SemanticGroup> split(ParsedSection section, List<String> paragraphs);

    record SemanticGroup(int startInclusive, int endInclusive, String topic) {
    }
}
