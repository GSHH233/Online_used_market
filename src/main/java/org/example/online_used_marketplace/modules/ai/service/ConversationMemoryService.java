package org.example.online_used_marketplace.modules.ai.service;

import java.util.List;

public interface ConversationMemoryService {

    List<String> getRecentMessages(String sessionId, int limit);

    void appendTurn(String sessionId, String question, String answer);
}
