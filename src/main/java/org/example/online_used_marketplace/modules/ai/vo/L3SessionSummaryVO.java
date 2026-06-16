package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class L3SessionSummaryVO {

    private String userId;

    private String sessionId;

    private String currentGoal;

    private String productName;

    private String category;

    private String brand;

    private String model;

    private List<String> knownFacts = new ArrayList<>();

    private List<String> missingFields = new ArrayList<>();

    private String lastRiskConclusion;

    private String lastPriceConclusion;

    private String lastPublishAdvice;

    private List<String> lastTools = new ArrayList<>();

    private String nextAsk;

    private Map<String, String> rawKv = new LinkedHashMap<>();

    private LocalDateTime updatedAt;

    private String promptBlock;
}
