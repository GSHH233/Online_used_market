package org.example.online_used_marketplace.modules.ai.service;

import org.example.online_used_marketplace.modules.ai.vo.EvalDashboardVO;

public interface EvalDashboardService {

    EvalDashboardVO dashboard(Integer recentLimit);
}
