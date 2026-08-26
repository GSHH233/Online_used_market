package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AiopsDiagnosisSupervisor {

    private final ObjectProvider<AiopsDiagnosisEnhancer> enhancerProvider;

    @Autowired
    public AiopsDiagnosisSupervisor(ObjectProvider<AiopsDiagnosisEnhancer> enhancerProvider) {
        this.enhancerProvider = enhancerProvider;
    }

    public AiopsDiagnosisSupervisor() {
        this.enhancerProvider = null;
    }

    public Diagnosis diagnose(AlertInvestigationContext context, List<SpecialistResult> specialistResults) {
        List<Evidence> evidence = new ArrayList<>();
        List<String> missingEvidence = new ArrayList<>();
        Map<String, Evidence> uniqueEvidence = new LinkedHashMap<>();
        EnumSet<SpecialistType> availableSources = EnumSet.noneOf(SpecialistType.class);
        boolean degraded = false;

        if (specialistResults != null) {
            for (SpecialistResult result : specialistResults) {
                if (result == null) {
                    degraded = true;
                    missingEvidence.add("unknown_specialist_result");
                    continue;
                }
                if (result.getStatus() != SpecialistStatus.COMPLETED) {
                    degraded = true;
                }
                if (result.getEvidence() != null) {
                    for (Evidence item : result.getEvidence()) {
                        if (item != null && item.getStatus() == EvidenceStatus.OBSERVED
                                && item.getEvidenceId() != null && !item.getEvidenceId().isBlank()) {
                            uniqueEvidence.putIfAbsent(item.getEvidenceId(), item);
                            if (item.getSource() != null) {
                                availableSources.add(item.getSource());
                            }
                        }
                    }
                }
                if (result.getMissingEvidence() != null) {
                    missingEvidence.addAll(result.getMissingEvidence());
                }
                if ((result.getEvidence() == null || result.getEvidence().isEmpty())
                        && result.getSpecialist() != null
                        && (result.getMissingEvidence() == null || result.getMissingEvidence().isEmpty())) {
                    missingEvidence.add(result.getSpecialist().name().toLowerCase() + "_result");
                }
            }
        }
        evidence.addAll(uniqueEvidence.values());
        double confidence = calculateConfidence(availableSources.size(), evidence.size());
        List<Hypothesis> hypotheses = buildHypotheses(context, evidence, confidence);
        Diagnosis deterministic = Diagnosis.builder()
                .evidence(evidence)
                .missingEvidence(distinct(missingEvidence))
                .rootCauseHypotheses(hypotheses)
                .confidence(confidence)
                .degraded(degraded || evidence.isEmpty())
                .build();
        return applyOptionalEnhancement(context, deterministic);
    }

    private Diagnosis applyOptionalEnhancement(AlertInvestigationContext context, Diagnosis deterministic) {
        if (enhancerProvider == null) {
            return deterministic;
        }
        AiopsDiagnosisEnhancer enhancer = enhancerProvider.getIfAvailable();
        if (enhancer == null) {
            return deterministic;
        }
        try {
            Diagnosis candidate = enhancer.enhance(
                    context == null ? new AlertInvestigationContext() : context.controlPlaneView(),
                    deterministic);
            if (candidate == null) {
                return deterministic;
            }
            Set<String> allowedEvidenceIds = deterministic.getEvidence().stream()
                    .map(Evidence::getEvidenceId)
                    .collect(java.util.stream.Collectors.toSet());
            List<Hypothesis> safeHypotheses = new ArrayList<>();
            if (candidate.getRootCauseHypotheses() != null) {
                for (Hypothesis hypothesis : candidate.getRootCauseHypotheses()) {
                    if (hypothesis == null || hypothesis.getStatement() == null
                            || hypothesis.getStatement().isBlank() || hypothesis.getEvidenceIds() == null) {
                        continue;
                    }
                    List<String> safeIds = hypothesis.getEvidenceIds().stream()
                            .filter(allowedEvidenceIds::contains)
                            .distinct()
                            .toList();
                    if (!safeIds.isEmpty()) {
                        hypothesis.setEvidenceIds(new ArrayList<>(safeIds));
                        hypothesis.setConfidence(Math.min(1D, Math.max(0D, hypothesis.getConfidence())));
                        safeHypotheses.add(hypothesis);
                    }
                }
            }
            if (!safeHypotheses.isEmpty()) {
                deterministic.setRootCauseHypotheses(safeHypotheses);
            }
            deterministic.setConfidence(Math.min(1D,
                    Math.max(deterministic.getConfidence(), Math.min(1D, candidate.getConfidence()))));
        } catch (RuntimeException ignored) {
            // A model enhancer is optional. Deterministic diagnosis remains authoritative.
        }
        return deterministic;
    }

    private double calculateConfidence(int sourceCount, int evidenceCount) {
        if (evidenceCount == 0) {
            return 0D;
        }
        double confidence = 0.20D
                + Math.min(sourceCount, 3) * 0.20D
                + Math.min(evidenceCount, 3) * 0.05D;
        if (sourceCount >= 2) {
            confidence += 0.10D;
        }
        return Math.min(1D, Math.round(confidence * 1000D) / 1000D);
    }

    private List<Hypothesis> buildHypotheses(AlertInvestigationContext context,
                                             List<Evidence> evidence,
                                             double confidence) {
        if (evidence.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> evidenceIds = evidence.stream().map(Evidence::getEvidenceId).toList();
        EnumSet<SpecialistType> sources = EnumSet.noneOf(SpecialistType.class);
        evidence.forEach(item -> {
            if (item.getSource() != null) {
                sources.add(item.getSource());
            }
        });
        String statement;
        if (sources.contains(SpecialistType.METRICS) && sources.contains(SpecialistType.CHANGE)) {
            statement = "指标异常与告警时间窗内的变更存在时序关联，优先核查变更影响";
        } else if (sources.contains(SpecialistType.CHANGE)) {
            statement = "告警时间窗内存在近期变更，可能与当前异常相关";
        } else if (sources.contains(SpecialistType.METRICS)) {
            statement = "指标源观测到与告警类型匹配的服务信号，需要结合独立验证确认根因";
        } else {
            statement = "结构化 trace 日志提供了当前事件的调查线索，但不足以单独确认根因";
        }
        String suffix = context == null || context.getAlertName() == null
                ? ""
                : "（告警：" + context.getAlertName().trim() + "）";
        Hypothesis hypothesis = Hypothesis.builder()
                .hypothesisId("hypothesis-1")
                .statement(statement + suffix)
                .confidence(confidence)
                .evidenceIds(new ArrayList<>(evidenceIds))
                .build();
        return List.of(hypothesis);
    }

    private List<String> distinct(List<String> values) {
        return values == null ? new ArrayList<>() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }
}
