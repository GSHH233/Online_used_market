package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

public interface AiopsRemediationPolicy {

    RemediationRecommendation recommend(AlertInvestigationContext context, Diagnosis diagnosis);
}
