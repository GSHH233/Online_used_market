package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.memory.MemoryScope;
import java.util.List;

public interface ConversationMemoryService {

    List<String> getRecentMessages(MemoryScope scope, int limit);

    void appendTurn(MemoryScope scope, String question, String answer);
}
