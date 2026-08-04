package org.example.online_used_marketplace.modules.ai.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QueryRewritePlanVO {
    private String originalQuery;
    private String mainQuery;
    private List<String> subQueries = new ArrayList<>();
    private List<String> synonymQueries = new ArrayList<>();
    private List<String> mustKeepTerms = new ArrayList<>();
    private List<String> negations = new ArrayList<>();
    private String rewriteReason;
    private String triggerReason;
    private boolean rewriteUsed;
    private boolean fallback;
    private String fallbackReason;
    private long elapsedMs;

    public List<WeightedQuery> weightedQueries() {
        List<WeightedQuery> out = new ArrayList<>();
        out.add(new WeightedQuery(originalQuery, "ORIGINAL", 1.0, true));
        if (rewriteUsed && mainQuery != null && !mainQuery.isBlank()) {
            out.add(new WeightedQuery(mainQuery, "MAIN", 1.0, true));
        }
        if (rewriteUsed) {
            subQueries.forEach(query -> out.add(new WeightedQuery(query, "SUB", 0.85, false)));
            synonymQueries.forEach(query -> out.add(new WeightedQuery(query, "SYNONYM", 0.65, false)));
        }
        return out;
    }

    public record WeightedQuery(String query, String type, double weight, boolean keywordEnabled) {
    }
}
