package org.example.online_used_marketplace.modules.ai.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.online_used_marketplace.modules.ai.service.L2MemoryService;
import org.example.online_used_marketplace.modules.ai.service.UserProfileService;
import org.example.online_used_marketplace.modules.ai.vo.L2MemoryHitVO;
import org.example.online_used_marketplace.modules.ai.vo.UserProfileVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private final L2MemoryService l2MemoryService;
    private final ObjectMapper objectMapper;

    @Override
    public UserProfileVO buildProfile(String userId, String sessionId) {
        UserProfileVO profile = new UserProfileVO();
        profile.setUserId(StringUtils.hasText(userId) ? userId.trim() : "anonymous");
        profile.setSessionId(StringUtils.hasText(sessionId) ? sessionId.trim() : "");

        List<L2MemoryHitVO> preferenceRows = l2MemoryService.retrieve(
                        profile.getUserId(),
                        profile.getSessionId(),
                        "偏好 品牌 预算 价格 成色 品类 交易 风险 文案",
                        30,
                        0.05D
                )
                .stream()
                .filter(one -> "PREFERENCE".equalsIgnoreCase(one.getMemoryType()))
                .toList();

        Map<String, String> raw = new LinkedHashMap<>();
        for (L2MemoryHitVO row : preferenceRows) {
            Map.Entry<String, String> entry = parsePreference(row);
            if (entry == null || !StringUtils.hasText(entry.getKey()) || !StringUtils.hasText(entry.getValue())) {
                continue;
            }
            raw.putIfAbsent(normalizeKey(entry.getKey()), entry.getValue().trim());
            if (StringUtils.hasText(row.getContent())) {
                profile.getEvidence().add(row.getContent());
            }
        }

        profile.setRawPreferences(raw);
        applyRawPreferences(profile, raw);
        profile.setPromptBlock(toPromptBlock(profile));
        return profile;
    }

    @Override
    public UserProfileVO mergeCurrentTurn(UserProfileVO profile, Map<String, String> currentTurnPreferences) {
        UserProfileVO target = profile == null ? new UserProfileVO() : profile;
        Map<String, String> merged = new LinkedHashMap<>();
        if (target.getRawPreferences() != null) {
            merged.putAll(target.getRawPreferences());
        }
        if (currentTurnPreferences != null) {
            for (Map.Entry<String, String> entry : currentTurnPreferences.entrySet()) {
                if (StringUtils.hasText(entry.getKey()) && StringUtils.hasText(entry.getValue())) {
                    merged.put(normalizeKey(entry.getKey()), entry.getValue().trim());
                }
            }
        }
        target.setRawPreferences(merged);
        applyRawPreferences(target, merged);
        target.setPromptBlock(toPromptBlock(target));
        return target;
    }

    @Override
    public Map<String, String> toPreferenceMap(UserProfileVO profile) {
        Map<String, String> out = new LinkedHashMap<>();
        if (profile == null) {
            return out;
        }
        putIfText(out, "brand", String.join(",", profile.getPreferredBrands()));
        putIfText(out, "category", String.join(",", profile.getPreferredCategories()));
        putIfText(out, "priceRange", profile.getPriceRange());
        putIfText(out, "condition", profile.getConditionPreference());
        putIfText(out, "transaction", profile.getTransactionPreference());
        putIfText(out, "riskTolerance", profile.getRiskTolerance());
        putIfText(out, "writingStyle", profile.getWritingStyle());
        if (profile.getRawPreferences() != null) {
            profile.getRawPreferences().forEach((key, value) -> putIfText(out, key, value));
        }
        return out;
    }

    @Override
    public String toPromptBlock(UserProfileVO profile) {
        if (profile == null || (profile.getRawPreferences() == null || profile.getRawPreferences().isEmpty())) {
            return "（无）";
        }
        return "preferredBrands=" + String.join(",", profile.getPreferredBrands())
                + "; preferredCategories=" + String.join(",", profile.getPreferredCategories())
                + "; priceRange=" + safe(profile.getPriceRange())
                + "; condition=" + safe(profile.getConditionPreference())
                + "; transaction=" + safe(profile.getTransactionPreference())
                + "; riskTolerance=" + safe(profile.getRiskTolerance())
                + "; writingStyle=" + safe(profile.getWritingStyle())
                + "\n约束：Profile 只能用于默认参数、排序和表达风格；不得覆盖本轮商品事实、RAG证据、风险规则或用户明确输入。";
    }

    private Map.Entry<String, String> parsePreference(L2MemoryHitVO row) {
        try {
            if (StringUtils.hasText(row.getMetadataJson())) {
                Map<String, String> metadata = objectMapper.readValue(row.getMetadataJson(), new TypeReference<Map<String, String>>() {});
                String key = metadata.get("key");
                String value = metadata.get("value");
                if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
                    return Map.entry(key.trim(), value.trim());
                }
            }
        } catch (Exception ignore) {
            // fallback to content parsing
        }

        String content = row.getContent();
        if (!StringUtils.hasText(content) || !content.startsWith("PREFERENCE|")) {
            return null;
        }
        String kv = content.substring("PREFERENCE|".length());
        int idx = kv.indexOf('=');
        if (idx <= 0 || idx >= kv.length() - 1) {
            return null;
        }
        return Map.entry(kv.substring(0, idx).trim(), kv.substring(idx + 1).trim());
    }

    private void applyRawPreferences(UserProfileVO profile, Map<String, String> raw) {
        profile.getPreferredBrands().clear();
        profile.getPreferredCategories().clear();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = normalizeKey(entry.getKey());
            String value = entry.getValue();
            if (!StringUtils.hasText(value)) {
                continue;
            }
            switch (key) {
                case "brand" -> addCsv(profile.getPreferredBrands(), value);
                case "category" -> addCsv(profile.getPreferredCategories(), value);
                case "priceRange" -> profile.setPriceRange(value);
                case "condition" -> profile.setConditionPreference(value);
                case "transaction" -> profile.setTransactionPreference(value);
                case "riskTolerance" -> profile.setRiskTolerance(value);
                case "writingStyle" -> profile.setWritingStyle(value);
                default -> {
                    // 保留在 rawPreferences 中供后续 profile schema 扩展。
                }
            }
        }
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        String lower = key.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "brand", "preferredbrand", "preferredbrands" -> "brand";
            case "category", "preferredcategory", "preferredcategories" -> "category";
            case "budget", "price", "pricerange" -> "priceRange";
            case "condition", "conditionpreference" -> "condition";
            case "trade", "transaction", "transactionpreference" -> "transaction";
            case "risk", "risktolerance" -> "riskTolerance";
            case "style", "writingstyle" -> "writingStyle";
            default -> key.trim();
        };
    }

    private void addCsv(List<String> target, String value) {
        for (String item : value.split(",|，|/|、")) {
            if (StringUtils.hasText(item)) {
                String trimmed = item.trim();
                if (!target.contains(trimmed)) {
                    target.add(trimmed);
                }
            }
        }
    }

    private void putIfText(Map<String, String> target, String key, String value) {
        if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
