package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.vo.QueryRewritePlanVO;

public interface QueryRewriteService {
    boolean shouldRewriteBeforeRetrieval(String query);
    QueryRewritePlanVO rewrite(String query, String triggerReason);
    QueryRewritePlanVO originalOnly(String query, String reason);
}
