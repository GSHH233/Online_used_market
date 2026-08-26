package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiopsCollaborationResult {

    private AlertInvestigationContext context;

    @Builder.Default
    private List<SpecialistResult> specialistResults = new ArrayList<>();

    private Diagnosis diagnosis;

    private RemediationRecommendation remediation;

    private long elapsedMs;

    @Builder.Default
    private List<SpecialistType> degradedSpecialists = new ArrayList<>();
}
