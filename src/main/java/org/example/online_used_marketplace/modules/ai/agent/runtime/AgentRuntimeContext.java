package org.example.online_used_marketplace.modules.ai.agent.runtime;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AgentRuntimeContext {

    private String contextId;
    private String agentType;
    private String traceId;
    private String userId;
    private String sessionId;
    private String role;
    private String mode;
    private String question;
    private int maxSteps;

    private String l3Summary = "";
    private Map<String, String> l3AutofillParams = new LinkedHashMap<>();
    private String profilePromptBlock = "";
    private Map<String, String> profilePreferences = new LinkedHashMap<>();
    private List<String> l1RecentMessages = new ArrayList<>();
    private List<String> l2MemoryLines = new ArrayList<>();
    private Map<String, Object> l2RecallAudit = new LinkedHashMap<>();
    private List<String> graphMemoryLines = new ArrayList<>();

    private String promptBlock = "";
    private int promptChars;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String l3OrEmpty() {
        return l3Summary == null ? "" : l3Summary;
    }

    public Map<String, Object> safeMetadata() {
        return metadata == null ? Map.of() : metadata;
    }
}
