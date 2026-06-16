package org.example.online_used_marketplace.modules.ai.agent.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.service.ConversationMemoryService;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.service.MemoryGraphService;
import org.example.online_used_marketplace.modules.ai.service.SessionSummaryService;
import org.example.online_used_marketplace.modules.ai.service.UserProfileService;
import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphEdgeVO;
import org.example.online_used_marketplace.modules.ai.vo.MemoryGraphNodeVO;
import org.example.online_used_marketplace.modules.ai.vo.UserProfileVO;
import org.example.online_used_marketplace.modules.user.entity.UserPrincipal;
import org.example.online_used_marketplace.modules.user.enums.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntimeContextBuilder {

    private static final int L1_LIMIT = 6;
    private static final int L2_TOP_K = 4;
    private static final double L2_THRESHOLD = 0.3D;
    private static final int GRAPH_LIMIT = 8;
    private static final int MAX_BLOCK_CHARS = 3200;
    private static final int MAX_LINE_CHARS = 180;

    private final ConversationMemoryService conversationMemoryService;
    private final L2MemoryService l2MemoryService;
    private final SessionSummaryService sessionSummaryService;
    private final UserProfileService userProfileService;
    private final MemoryGraphService memoryGraphService;

    public AgentRuntimeContext build(String agentType,
                                     String sessionId,
                                     String traceId,
                                     String mode,
                                     String question,
                                     int maxSteps) {
        String normalizedSessionId = safe(sessionId, "anonymous");
        String normalizedMode = "INTERNAL".equalsIgnoreCase(mode) ? "INTERNAL" : "USER";
        String userId = resolveUserId(normalizedSessionId);
        String role = resolveRole(normalizedMode);

        AgentRuntimeContext context = new AgentRuntimeContext();
        context.setAgentType(safe(agentType, "agent"));
        context.setTraceId(safe(traceId, ""));
        context.setUserId(userId);
        context.setSessionId(normalizedSessionId);
        context.setRole(role);
        context.setMode(normalizedMode);
        context.setQuestion(safe(question, ""));
        context.setMaxSteps(maxSteps);
        context.setContextId(buildContextId(agentType, normalizedSessionId, traceId, question));

        String l3Summary = safeGet(() -> sessionSummaryService.getSummary(normalizedSessionId), "l3Summary", "");
        Map<String, String> l3Autofill = safeGet(() -> sessionSummaryService.getAutofillParams(normalizedSessionId), "l3Autofill", Map.of());
        UserProfileVO profile = safeGet(() -> userProfileService.buildProfile(userId, normalizedSessionId), "profile", null);
        Map<String, String> profilePreferences = profile == null
                ? Map.of()
                : safeGet(() -> userProfileService.toPreferenceMap(profile), "profilePreferences", Map.of());
        String profileBlock = profile == null ? "" : safe(profile.getPromptBlock(), "");

        List<String> l1Recent = safeGet(() -> conversationMemoryService.getRecentMessages(normalizedSessionId, L1_LIMIT), "l1Recent", List.of());
        List<String> l2Lines = safeGet(() -> l2MemoryService.retrieve(userId, normalizedSessionId, safe(question, ""), L2_TOP_K, L2_THRESHOLD)
                        .stream()
                        .map(this::formatL2Memory)
                        .filter(StringUtils::hasText)
                        .toList(),
                "l2Retrieve", List.of());
        List<String> graphLines = buildGraphMemoryLines(userId);

        context.setL3Summary(l3Summary);
        context.setL3AutofillParams(new LinkedHashMap<>(l3Autofill));
        context.setProfilePromptBlock(profileBlock);
        context.setProfilePreferences(new LinkedHashMap<>(profilePreferences));
        context.setL1RecentMessages(new ArrayList<>(l1Recent));
        context.setL2MemoryLines(new ArrayList<>(l2Lines));
        context.setGraphMemoryLines(new ArrayList<>(graphLines));

        String promptBlock = buildPromptBlock(context);
        context.setPromptBlock(promptBlock);
        context.setPromptChars(promptBlock.length());
        context.setMetadata(buildMetadata(context));
        return context;
    }

    private String buildPromptBlock(AgentRuntimeContext context) {
        List<String> lines = new ArrayList<>();
        lines.add("Agent Runtime Context");
        lines.add("Priority: current user input > tool observations/RAG evidence > L3 session summary > L2/Profile/Graph memory > L1 recent turns.");
        lines.add("Conflict rule: memory may fill missing parameters, but must not override current turn facts, tool observations, risk rules, or RAG evidence.");
        addBlock(lines, "L3 session summary", context.getL3Summary());
        addBlock(lines, "L3 autofill params", context.getL3AutofillParams().isEmpty() ? "" : context.getL3AutofillParams().toString());
        addBlock(lines, "Profile", context.getProfilePromptBlock());
        addListBlock(lines, "L2 semantic memories", context.getL2MemoryLines());
        addListBlock(lines, "Memory graph", context.getGraphMemoryLines());
        addListBlock(lines, "L1 recent conversation", compactList(context.getL1RecentMessages(), MAX_LINE_CHARS));
        return compactBlock(String.join("\n", lines), MAX_BLOCK_CHARS);
    }

    private void addBlock(List<String> lines, String title, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        lines.add(title + ":");
        lines.add(compact(text, 700));
    }

    private void addListBlock(List<String> lines, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        lines.add(title + ":");
        values.stream()
                .filter(StringUtils::hasText)
                .limit(6)
                .map(value -> "- " + compact(value, MAX_LINE_CHARS))
                .forEach(lines::add);
    }

    private List<String> buildGraphMemoryLines(String userId) {
        List<String> out = new ArrayList<>();
        List<MemoryGraphNodeVO> nodes = safeGet(() -> memoryGraphService.listNodes(userId, null, GRAPH_LIMIT), "graphNodes", List.of());
        for (MemoryGraphNodeVO node : nodes) {
            if (node == null || !StringUtils.hasText(node.getLabel())) {
                continue;
            }
            out.add("node[" + safe(node.getNodeType(), "") + "] "
                    + compact(node.getLabel(), 60)
                    + ", topic=" + safe(node.getTopic(), "")
                    + ", importance=" + formatDouble(node.getImportance()));
        }
        List<MemoryGraphEdgeVO> edges = safeGet(() -> memoryGraphService.listEdges(userId, null, null, GRAPH_LIMIT), "graphEdges", List.of());
        for (MemoryGraphEdgeVO edge : edges) {
            if (edge == null || !StringUtils.hasText(edge.getFromLabel()) || !StringUtils.hasText(edge.getToLabel())) {
                continue;
            }
            out.add("edge " + compact(edge.getFromLabel(), 50)
                    + " -> " + compact(edge.getToLabel(), 50)
                    + ", type=" + safe(edge.getEdgeType(), "")
                    + ", confidence=" + formatDouble(edge.getConfidence()));
        }
        return out.stream().limit(GRAPH_LIMIT).toList();
    }

    private Map<String, Object> buildMetadata(AgentRuntimeContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contextId", context.getContextId());
        metadata.put("agentType", context.getAgentType());
        metadata.put("userId", context.getUserId());
        metadata.put("sessionId", context.getSessionId());
        metadata.put("role", context.getRole());
        metadata.put("mode", context.getMode());
        metadata.put("promptChars", context.getPromptChars());
        metadata.put("hasL3", StringUtils.hasText(context.getL3Summary()));
        metadata.put("l3AutofillFields", context.getL3AutofillParams().size());
        metadata.put("profileFields", context.getProfilePreferences().size());
        metadata.put("l1Count", context.getL1RecentMessages().size());
        metadata.put("l2Count", context.getL2MemoryLines().size());
        metadata.put("graphCount", context.getGraphMemoryLines().size());
        return metadata;
    }

    private String formatL2Memory(L2MemoryHitVO hit) {
        if (hit == null || !StringUtils.hasText(hit.getContent())) {
            return "";
        }
        return "[" + safe(hit.getMemoryType(), "MEMORY")
                + "|score=" + formatDouble(hit.getScore())
                + "|importance=" + formatDouble(hit.getImportanceScore())
                + "] " + compact(hit.getContent(), MAX_LINE_CHARS);
    }

    private String resolveUserId(String fallbackSessionId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.getUserId() != null) {
            return String.valueOf(principal.getUserId());
        }
        return fallbackSessionId;
    }

    private String resolveRole(String fallbackMode) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return UserRole.normalize(principal.getRole());
        }
        return UserRole.normalize(fallbackMode);
    }

    private String buildContextId(String agentType, String sessionId, String traceId, String question) {
        int hash = Objects.hash(safe(agentType, ""), safe(sessionId, ""), safe(traceId, ""), safe(question, ""));
        return safe(agentType, "agent") + ":" + Integer.toHexString(hash);
    }

    private <T> T safeGet(Supplier<T> supplier, String source, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (Exception ex) {
            log.debug("build agent runtime context skipped {}, reason={}", source, ex.getMessage());
            return fallback;
        }
    }

    private List<String> compactList(List<String> values, int maxChars) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(value -> compact(value, maxChars))
                .toList();
    }

    private String compact(String value, int maxChars) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String compactBlock(String value, int maxChars) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String compactLine = line.replaceAll("[\\t\\x0B\\f ]+", " ").trim();
            if (StringUtils.hasText(compactLine)) {
                lines.add(compactLine);
            }
        }
        String compacted = String.join("\n", lines);
        if (compacted.length() <= maxChars) {
            return compacted;
        }
        return compacted.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String safe(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String formatDouble(Double value) {
        if (value == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
