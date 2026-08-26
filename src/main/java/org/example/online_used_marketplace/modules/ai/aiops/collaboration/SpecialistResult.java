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
public class SpecialistResult {

    private SpecialistType specialist;

    @Builder.Default
    private SpecialistStatus status = SpecialistStatus.COMPLETED;

    @Builder.Default
    private List<Evidence> evidence = new ArrayList<>();

    @Builder.Default
    private List<String> missingEvidence = new ArrayList<>();

    private long elapsedMs;

    private String errorMessage;

    public static SpecialistResult completed(SpecialistType specialist, List<Evidence> evidence) {
        return SpecialistResult.builder()
                .specialist(specialist)
                .status(SpecialistStatus.COMPLETED)
                .evidence(evidence == null ? new ArrayList<>() : new ArrayList<>(evidence))
                .build();
    }

    public static SpecialistResult degraded(SpecialistType specialist, String missing, String errorMessage) {
        List<String> missingItems = new ArrayList<>();
        if (missing != null && !missing.isBlank()) {
            missingItems.add(missing);
        }
        return SpecialistResult.builder()
                .specialist(specialist)
                .status(SpecialistStatus.DEGRADED)
                .missingEvidence(missingItems)
                .errorMessage(errorMessage)
                .build();
    }

    public static SpecialistResult timeout(SpecialistType specialist) {
        return SpecialistResult.builder()
                .specialist(specialist)
                .status(SpecialistStatus.TIMEOUT)
                .missingEvidence(List.of(specialist.name().toLowerCase() + "_timeout"))
                .errorMessage("specialist timeout")
                .build();
    }
}
