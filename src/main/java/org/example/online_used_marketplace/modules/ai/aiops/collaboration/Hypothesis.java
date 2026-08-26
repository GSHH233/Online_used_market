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
public class Hypothesis {

    private String hypothesisId;

    private String statement;

    private double confidence;

    @Builder.Default
    private List<String> evidenceIds = new ArrayList<>();
}
