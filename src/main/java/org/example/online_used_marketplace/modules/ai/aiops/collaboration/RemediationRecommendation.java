package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemediationRecommendation {

    private String actionCode;

    @Builder.Default
    private Map<String, Object> params = new LinkedHashMap<>();

    private String reason;

    @Builder.Default
    private boolean approvalRequired = true;

    public static RemediationRecommendation empty(String reason) {
        return RemediationRecommendation.builder()
                .actionCode("")
                .reason(reason)
                .params(new LinkedHashMap<>())
                .approvalRequired(true)
                .build();
    }
}
