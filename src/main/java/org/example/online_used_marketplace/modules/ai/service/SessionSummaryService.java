package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.vo.L3SessionSummaryVO;

import java.util.Map;

public interface SessionSummaryService {

    String getSummary(String sessionId);

    L3SessionSummaryVO getStructuredSummary(String sessionId);

    Map<String, String> getAutofillParams(String sessionId);

    void refreshSummaryIfNeeded(String sessionId, String question, String answer);
}
