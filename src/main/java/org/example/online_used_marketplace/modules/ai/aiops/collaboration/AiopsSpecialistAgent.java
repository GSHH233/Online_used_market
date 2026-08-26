package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

public interface AiopsSpecialistAgent {

    SpecialistType type();

    SpecialistResult investigate(AlertInvestigationContext context);

    default String name() {
        return type().name().toLowerCase();
    }
}
