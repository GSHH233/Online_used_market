package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.token.TokenUsageRecord;
import org.example.online_used_marketplace.modules.ai.vo.TokenUsageMetricsVO;

import java.util.List;

public interface TokenUsageService {

    void record(TokenUsageRecord record);

    List<TokenUsageRecord> recent(String traceId, Integer limit);

    TokenUsageMetricsVO metrics(Integer recentLimit);
}
