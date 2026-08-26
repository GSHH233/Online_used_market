package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

public interface AiopsCollaborationEngine {

    AiopsCollaborationResult investigate(AlertInvestigationContext context);

    default AiopsCollaborationResult collaborate(AlertInvestigationContext context) {
        return investigate(context);
    }
}
