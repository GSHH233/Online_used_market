package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.service.ChatGenerationService;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.service.SessionSummaryService;
import org.example.online_used_marketplace.modules.ai.vo.L3SessionSummaryVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L3 会话任务摘要：KV结构化状态，规则提取优先，LLM提取补充。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InMemorySessionSummaryServiceImpl implements SessionSummaryService {

    private static final int LLM_REFRESH_EVERY_TURNS = 3;
    private static final double L3_SYNC_IMPORTANCE = 0.8D;

    private final Map<String, L3SessionSummaryVO> summaryStore = new ConcurrentHashMap<>();
    private final Map<String, Integer> turnCounter = new ConcurrentHashMap<>();
    private final ChatGenerationService chatGenerationService;
    private final L2MemoryService l2MemoryService;
    private final ObjectMapper objectMapper;

    @Override
    public String getSummary(String sessionId) {
        L3SessionSummaryVO summary = getStructuredSummary(sessionId);
        return summary == null ? "" : summary.getPromptBlock();
    }

    @Override
    public L3SessionSummaryVO getStructuredSummary(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        String sid = sessionId.trim();
        L3SessionSummaryVO cached = summaryStore.get(sid);
        if (cached != null) {
            return cached;
        }
        L3SessionSummaryVO restored = restoreFromL2(sid);
        if (restored != null) {
            summaryStore.put(sid, restored);
        }
        return restored;
    }

    @Override
    public Map<String, String> getAutofillParams(String sessionId) {
        L3SessionSummaryVO summary = getStructuredSummary(sessionId);
        Map<String, String> params = new LinkedHashMap<>();
        if (summary == null) {
            return params;
        }
        putIfText(params, "productName", summary.getProductName());
        putIfText(params, "category", summary.getCategory());
        putIfText(params, "brand", summary.getBrand());
        putIfText(params, "model", summary.getModel());
        return params;
    }

    @Override
    public void refreshSummaryIfNeeded(String sessionId, String question, String answer) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        String sid = sessionId.trim();
        int turns = turnCounter.merge(sid, 1, Integer::sum);

        L3SessionSummaryVO old = summaryStore.getOrDefault(sid, newSummary(sid));
        L3SessionSummaryVO llm = (turns == 1 || turns % LLM_REFRESH_EVERY_TURNS == 0)
                ? extractByLlm(old, question, answer)
                : new L3SessionSummaryVO();
        L3SessionSummaryVO rule = extractByRule(question, answer);
        L3SessionSummaryVO merged = merge(old, llm, rule);
        merged.setUpdatedAt(LocalDateTime.now());
        merged.setPromptBlock(toPromptBlock(merged));
        summaryStore.put(sid, merged);
        syncToL2(merged);
    }

    private L3SessionSummaryVO newSummary(String sessionId) {
        L3SessionSummaryVO vo = new L3SessionSummaryVO();
        vo.setUserId(sessionId);
        vo.setSessionId(sessionId);
        vo.setUpdatedAt(LocalDateTime.now());
        vo.setPromptBlock("（无）");
        return vo;
    }

    private L3SessionSummaryVO extractByRule(String question, String answer) {
        String text = (safe(question) + " " + safe(answer)).trim();
        String lower = text.toLowerCase(Locale.ROOT);
        L3SessionSummaryVO vo = new L3SessionSummaryVO();
        vo.setCurrentGoal(detectGoal(lower));
        vo.setCategory(detectCategory(text, lower));
        vo.setBrand(detectBrand(lower));
        vo.setModel(detectModel(text));
        vo.setProductName(detectProductName(vo));
        vo.setKnownFacts(detectKnownFacts(text, lower));
        vo.setMissingFields(detectMissingFields(vo.getCategory(), text, lower));
        vo.setLastRiskConclusion(extractSentence(answer, "风险", "禁售", "不能发布", "高风险"));
        vo.setLastPriceConclusion(extractSentence(answer, "价格", "定价", "估价", "下调", "扣减"));
        vo.setLastPublishAdvice(extractSentence(answer, "标题", "描述", "建议", "写"));
        vo.setNextAsk(extractSentence(answer, "补充", "缺", "请提供", "需要"));
        return vo;
    }

    private L3SessionSummaryVO extractByLlm(L3SessionSummaryVO old, String question, String answer) {
        String prompt = """
                从本轮对话中提取 L3 会话任务状态，输出严格 JSON，不要解释。
                字段：currentGoal, productName, category, brand, model, knownFacts(array), missingFields(array),
                lastRiskConclusion, lastPriceConclusion, lastPublishAdvice, nextAsk。
                规则：只记录当前会话任务事实，不要写入长期偏好；用户本轮明确事实优先。
                旧状态：
                %s
                用户：
                %s
                助手：
                %s
                """.formatted(old == null ? "{}" : safe(old.getPromptBlock()), safe(question), safe(answer));
        try {
            String raw = chatGenerationService.generate(prompt);
            Map<String, Object> map = objectMapper.readValue(sanitizeJson(raw), new TypeReference<Map<String, Object>>() {});
            return fromMap(map);
        } catch (Exception ex) {
            log.debug("L3 LLM extractor skipped, reason={}", ex.getMessage());
            return new L3SessionSummaryVO();
        }
    }

    private L3SessionSummaryVO merge(L3SessionSummaryVO old, L3SessionSummaryVO llm, L3SessionSummaryVO rule) {
        L3SessionSummaryVO out = old == null ? new L3SessionSummaryVO() : copy(old);
        fill(out, llm, false);
        fill(out, rule, true);
        out.setKnownFacts(mergeList(old == null ? List.of() : old.getKnownFacts(), llm.getKnownFacts(), rule.getKnownFacts()));
        out.setMissingFields(mergeList(old == null ? List.of() : old.getMissingFields(), llm.getMissingFields(), rule.getMissingFields()));
        out.setLastTools(mergeList(old == null ? List.of() : old.getLastTools(), llm.getLastTools(), rule.getLastTools()));
        out.setRawKv(toKv(out));
        return out;
    }

    private void fill(L3SessionSummaryVO target, L3SessionSummaryVO source, boolean override) {
        if (source == null) {
            return;
        }
        if (override || !StringUtils.hasText(target.getCurrentGoal())) putIfText(target::setCurrentGoal, source.getCurrentGoal());
        if (override || !StringUtils.hasText(target.getProductName())) putIfText(target::setProductName, source.getProductName());
        if (override || !StringUtils.hasText(target.getCategory())) putIfText(target::setCategory, source.getCategory());
        if (override || !StringUtils.hasText(target.getBrand())) putIfText(target::setBrand, source.getBrand());
        if (override || !StringUtils.hasText(target.getModel())) putIfText(target::setModel, source.getModel());
        if (override || !StringUtils.hasText(target.getLastRiskConclusion())) putIfText(target::setLastRiskConclusion, source.getLastRiskConclusion());
        if (override || !StringUtils.hasText(target.getLastPriceConclusion())) putIfText(target::setLastPriceConclusion, source.getLastPriceConclusion());
        if (override || !StringUtils.hasText(target.getLastPublishAdvice())) putIfText(target::setLastPublishAdvice, source.getLastPublishAdvice());
        if (override || !StringUtils.hasText(target.getNextAsk())) putIfText(target::setNextAsk, source.getNextAsk());
    }

    private L3SessionSummaryVO copy(L3SessionSummaryVO src) {
        L3SessionSummaryVO out = new L3SessionSummaryVO();
        out.setUserId(src.getUserId());
        out.setSessionId(src.getSessionId());
        out.setCurrentGoal(src.getCurrentGoal());
        out.setProductName(src.getProductName());
        out.setCategory(src.getCategory());
        out.setBrand(src.getBrand());
        out.setModel(src.getModel());
        out.setKnownFacts(new ArrayList<>(src.getKnownFacts()));
        out.setMissingFields(new ArrayList<>(src.getMissingFields()));
        out.setLastRiskConclusion(src.getLastRiskConclusion());
        out.setLastPriceConclusion(src.getLastPriceConclusion());
        out.setLastPublishAdvice(src.getLastPublishAdvice());
        out.setLastTools(new ArrayList<>(src.getLastTools()));
        out.setNextAsk(src.getNextAsk());
        out.setRawKv(new LinkedHashMap<>(src.getRawKv()));
        out.setUpdatedAt(src.getUpdatedAt());
        out.setPromptBlock(src.getPromptBlock());
        return out;
    }

    private L3SessionSummaryVO fromMap(Map<String, Object> map) {
        L3SessionSummaryVO vo = new L3SessionSummaryVO();
        if (map == null) {
            return vo;
        }
        vo.setCurrentGoal(str(map.get("currentGoal")));
        vo.setProductName(str(map.get("productName")));
        vo.setCategory(str(map.get("category")));
        vo.setBrand(str(map.get("brand")));
        vo.setModel(str(map.get("model")));
        vo.setKnownFacts(list(map.get("knownFacts")));
        vo.setMissingFields(list(map.get("missingFields")));
        vo.setLastRiskConclusion(str(map.get("lastRiskConclusion")));
        vo.setLastPriceConclusion(str(map.get("lastPriceConclusion")));
        vo.setLastPublishAdvice(str(map.get("lastPublishAdvice")));
        vo.setNextAsk(str(map.get("nextAsk")));
        return vo;
    }

    private String toPromptBlock(L3SessionSummaryVO vo) {
        if (vo == null) {
            return "（无）";
        }
        List<String> lines = new ArrayList<>();
        addLine(lines, "currentGoal", vo.getCurrentGoal());
        addLine(lines, "productName", vo.getProductName());
        addLine(lines, "category", vo.getCategory());
        addLine(lines, "brand", vo.getBrand());
        addLine(lines, "model", vo.getModel());
        if (!vo.getKnownFacts().isEmpty()) lines.add("knownFacts=" + String.join("、", vo.getKnownFacts()));
        if (!vo.getMissingFields().isEmpty()) lines.add("missingFields=" + String.join("、", vo.getMissingFields()));
        addLine(lines, "lastRiskConclusion", vo.getLastRiskConclusion());
        addLine(lines, "lastPriceConclusion", vo.getLastPriceConclusion());
        addLine(lines, "lastPublishAdvice", vo.getLastPublishAdvice());
        addLine(lines, "nextAsk", vo.getNextAsk());
        if (lines.isEmpty()) {
            return "（无）";
        }
        lines.add("约束：L3只用于当前会话任务延续和工具参数补全；不得覆盖本轮用户输入、RAG证据或风险规则。");
        return String.join("\n", lines);
    }

    private void syncToL2(L3SessionSummaryVO vo) {
        if (vo == null || !StringUtils.hasText(vo.getSessionId()) || !StringUtils.hasText(vo.getPromptBlock())) {
            return;
        }
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("schema", "l3_session_summary_v1");
            metadata.put("kv", vo.getRawKv());
            metadata.put("source", "l3_rule_llm_merge");
            String content = "SESSION_SUMMARY|" + vo.getPromptBlock();
            l2MemoryService.saveMemory(vo.getUserId(), vo.getSessionId(), "SESSION_SUMMARY", content, L3_SYNC_IMPORTANCE, objectMapper.writeValueAsString(metadata));
        } catch (Exception ex) {
            log.debug("sync L3 summary to L2 skipped, reason={}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private L3SessionSummaryVO restoreFromL2(String sessionId) {
        try {
            return l2MemoryService.retrieve(sessionId, sessionId, "SESSION_SUMMARY", 1, 0D)
                    .stream()
                    .filter(one -> "SESSION_SUMMARY".equalsIgnoreCase(one.getMemoryType()))
                    .findFirst()
                    .map(hit -> {
                        L3SessionSummaryVO vo = newSummary(sessionId);
                        try {
                            Map<String, Object> metadata = objectMapper.readValue(hit.getMetadataJson(), new TypeReference<Map<String, Object>>() {});
                            Object kvObj = metadata.get("kv");
                            if (kvObj instanceof Map<?, ?> kv) {
                                vo.setRawKv(toStringMap((Map<?, ?>) kv));
                                applyKv(vo, vo.getRawKv());
                            }
                        } catch (Exception ignore) {
                            // fallback to content below
                        }
                        if (!StringUtils.hasText(vo.getPromptBlock())) {
                            vo.setPromptBlock(hit.getContent());
                        } else {
                            vo.setPromptBlock(toPromptBlock(vo));
                        }
                        return vo;
                    })
                    .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, String> toStringMap(Map<?, ?> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> putIfText(out, String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return out;
    }

    private void applyKv(L3SessionSummaryVO vo, Map<String, String> kv) {
        if (kv == null) {
            return;
        }
        vo.setCurrentGoal(kv.getOrDefault("currentGoal", ""));
        vo.setProductName(kv.getOrDefault("productName", ""));
        vo.setCategory(kv.getOrDefault("category", ""));
        vo.setBrand(kv.getOrDefault("brand", ""));
        vo.setModel(kv.getOrDefault("model", ""));
        vo.setKnownFacts(splitList(kv.get("knownFacts")));
        vo.setMissingFields(splitList(kv.get("missingFields")));
        vo.setLastRiskConclusion(kv.getOrDefault("lastRiskConclusion", ""));
        vo.setLastPriceConclusion(kv.getOrDefault("lastPriceConclusion", ""));
        vo.setLastPublishAdvice(kv.getOrDefault("lastPublishAdvice", ""));
        vo.setNextAsk(kv.getOrDefault("nextAsk", ""));
        vo.setPromptBlock(toPromptBlock(vo));
    }

    private List<String> splitList(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : text.split("、|,|，")) {
            if (StringUtils.hasText(part)) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private Map<String, String> toKv(L3SessionSummaryVO vo) {
        Map<String, String> kv = new LinkedHashMap<>();
        putIfText(kv, "currentGoal", vo.getCurrentGoal());
        putIfText(kv, "productName", vo.getProductName());
        putIfText(kv, "category", vo.getCategory());
        putIfText(kv, "brand", vo.getBrand());
        putIfText(kv, "model", vo.getModel());
        putIfText(kv, "knownFacts", String.join("、", vo.getKnownFacts()));
        putIfText(kv, "missingFields", String.join("、", vo.getMissingFields()));
        putIfText(kv, "lastRiskConclusion", vo.getLastRiskConclusion());
        putIfText(kv, "lastPriceConclusion", vo.getLastPriceConclusion());
        putIfText(kv, "lastPublishAdvice", vo.getLastPublishAdvice());
        putIfText(kv, "nextAsk", vo.getNextAsk());
        return kv;
    }

    private String detectGoal(String lower) {
        if (lower.contains("价格") || lower.contains("定价") || lower.contains("估价")) return "判断价格/定价";
        if (lower.contains("标题") || lower.contains("描述") || lower.contains("怎么写")) return "生成发布标题和描述建议";
        if (lower.contains("风险") || lower.contains("能不能卖") || lower.contains("禁售")) return "判断发布风险";
        return "";
    }

    private String detectCategory(String text, String lower) {
        Map<String, String> dict = Map.ofEntries(
                Map.entry("显卡", "显卡"), Map.entry("手机", "手机"), Map.entry("iphone", "手机"),
                Map.entry("macbook", "笔记本电脑"), Map.entry("笔记本", "笔记本电脑"), Map.entry("相机", "相机"),
                Map.entry("无人机", "无人机"), Map.entry("耳机", "耳机"), Map.entry("手表", "手表"),
                Map.entry("电子烟", "禁售品"), Map.entry("药品", "禁售品"), Map.entry("门票", "票务")
        );
        for (Map.Entry<String, String> e : dict.entrySet()) {
            if (lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return "";
    }

    private String detectBrand(String lower) {
        if (lower.contains("apple") || lower.contains("苹果") || lower.contains("iphone") || lower.contains("macbook") || lower.contains("airpods")) return "Apple";
        if (lower.contains("rtx")) return "NVIDIA";
        if (lower.contains("华为")) return "Huawei";
        if (lower.contains("小米")) return "Xiaomi";
        return "";
    }

    private String detectModel(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)(RTX\\s?\\d{3,4}|iPhone\\s?\\d{1,2}\\s?\\w*|MacBook\\s?Air\\s?M1|AirPods\\s?\\w*)")
                .matcher(text);
        return matcher.find() ? matcher.group(1).replaceAll("\\s+", " ").trim() : "";
    }

    private String detectProductName(L3SessionSummaryVO vo) {
        if (StringUtils.hasText(vo.getModel()) && StringUtils.hasText(vo.getCategory())) return vo.getModel() + " " + vo.getCategory();
        if (StringUtils.hasText(vo.getModel())) return vo.getModel();
        return vo.getCategory();
    }

    private List<String> detectKnownFacts(String text, String lower) {
        List<String> facts = new ArrayList<>();
        for (String term : List.of("无发票", "有发票", "全新未拆", "跑分截图", "温度正常", "温度偏高", "快门3万次", "配件齐全", "无原盒", "仅主机", "缺电池", "电池健康85%", "电池健康86%")) {
            if (lower.contains(term.toLowerCase(Locale.ROOT))) {
                facts.add(term);
            }
        }
        return facts;
    }

    private List<String> detectMissingFields(String category, String text, String lower) {
        List<String> missing = new ArrayList<>();
        if ("显卡".equals(category)) addMissing(missing, lower, Map.of("品牌", "品牌", "显存", "显存", "矿卡", "是否矿卡", "保修", "保修", "箱说", "箱说"));
        if ("相机".equals(category)) addMissing(missing, lower, Map.of("型号", "型号", "传感器", "传感器状态", "快门", "快门截图", "配件", "配件"));
        if ("手机".equals(category)) addMissing(missing, lower, Map.of("电池", "电池健康", "维修", "维修史", "配件", "配件", "发票", "发票"));
        return missing;
    }

    private void addMissing(List<String> missing, String lower, Map<String, String> required) {
        for (Map.Entry<String, String> e : required.entrySet()) {
            if (!lower.contains(e.getKey())) {
                missing.add(e.getValue());
            }
        }
    }

    private String extractSentence(String text, String... markers) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        for (String sentence : text.split("(?<=[。！？!?])|\\n")) {
            for (String marker : markers) {
                if (sentence.contains(marker)) {
                    return truncate(sentence.trim(), 120);
                }
            }
        }
        return "";
    }

    private List<String> mergeList(List<String> oldList, List<String> llmList, List<String> ruleList) {
        Set<String> set = new LinkedHashSet<>();
        addAll(set, oldList);
        addAll(set, llmList);
        addAll(set, ruleList);
        return new ArrayList<>(set).stream().filter(StringUtils::hasText).limit(12).toList();
    }

    private void addAll(Set<String> set, List<String> values) {
        if (values == null) return;
        for (String value : values) {
            if (StringUtils.hasText(value)) set.add(value.trim());
        }
    }

    private void putIfText(Map<String, String> target, String key, String value) {
        if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private void putIfText(java.util.function.Consumer<String> setter, String value) {
        if (StringUtils.hasText(value)) {
            setter.accept(value.trim());
        }
    }

    private void addLine(List<String> lines, String key, String value) {
        if (StringUtils.hasText(value)) {
            lines.add(key + "=" + value.trim());
        }
    }

    private List<String> list(Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        return raw.stream().map(this::str).filter(StringUtils::hasText).toList();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safe(String text) {
        return StringUtils.hasText(text) ? text.trim() : "";
    }

    private String sanitizeJson(String raw) {
        if (!StringUtils.hasText(raw)) return "{}";
        String text = raw.trim().replaceAll("^```(?:json)?", "").replaceAll("```$", "").trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : "{}";
    }

    private String truncate(String text, int max) {
        if (!StringUtils.hasText(text)) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
