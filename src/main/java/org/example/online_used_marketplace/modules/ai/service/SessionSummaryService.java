package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.memory.MemoryScope;
import org.example.online_used_marketplace.modules.ai.vo.L3SessionSummaryVO;

import java.util.Map;

public interface SessionSummaryService {

    String getSummary(MemoryScope scope);

    L3SessionSummaryVO getStructuredSummary(MemoryScope scope);

    Map<String, String> getAutofillParams(MemoryScope scope);

    void refreshSummaryIfNeeded(MemoryScope scope, String question, String answer);
}
