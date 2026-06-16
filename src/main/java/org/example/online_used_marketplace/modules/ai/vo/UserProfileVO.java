package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L2 长期记忆聚合出的结构化用户画像。
 */
@Data
public class UserProfileVO {

    private String userId;

    private String sessionId;

    private List<String> preferredBrands = new ArrayList<>();

    private List<String> preferredCategories = new ArrayList<>();

    private String priceRange;

    private String conditionPreference;

    private String transactionPreference;

    private String riskTolerance;

    private String writingStyle;

    private Map<String, String> rawPreferences = new LinkedHashMap<>();

    private List<String> evidence = new ArrayList<>();

    private String promptBlock;
}
