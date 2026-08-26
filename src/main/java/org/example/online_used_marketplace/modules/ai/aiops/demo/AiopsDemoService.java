package org.example.online_used_marketplace.modules.ai.aiops.demo;

import org.example.online_used_marketplace.modules.ai.aiops.demo.vo.AiopsDemoServiceStateVO;

public interface AiopsDemoService {

    void injectFault(String service, Double errorRatio, String traceId);

    void resetFault(String service, String traceId);

    AiopsDemoServiceStateVO getState(String service);
}
