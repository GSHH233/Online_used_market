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
        SignalAnalysis analysis = analyze(evidence, missingEvidence, degraded, availableSources.size());
        List<Hypothesis> hypotheses = buildHypotheses(context, evidence, confidence, analysis);
        Diagnosis deterministic = Diagnosis.builder()
                .evidence(evidence)
                .missingEvidence(distinct(missingEvidence))
                .conflictingEvidenceIds(analysis.conflictingEvidenceIds())
                .followUpQuestions(analysis.followUpQuestions())
                .rootCauseHypotheses(hypotheses)
                .confidence(confidence)
                .conclusionStatus(analysis.conclusionStatus())
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
                                             double confidence,
                                             SignalAnalysis analysis) {
        if (evidence.isEmpty()) {
            return new ArrayList<>();
        }
        String code = analysis.hypothesisCode();
        String statement = analysis.statement();
        String suffix = context == null || context.getAlertName() == null
                ? ""
                : "（告警：" + context.getAlertName().trim() + "）";
        Hypothesis hypothesis = Hypothesis.builder()
                .hypothesisId(code)
                .statement(statement + suffix)
                .confidence(confidence)
                .evidenceIds(evidence.stream().map(Evidence::getEvidenceId).distinct().toList())
                .build();
        return List.of(hypothesis);
    }

    private SignalAnalysis analyze(List<Evidence> evidence,
                                   List<String> missingEvidence,
                                   boolean degraded,
                                   int sourceCount) {
        Map<String, List<String>> idsByCode = new LinkedHashMap<>();
        for (Evidence item : evidence) {
            Object raw = item.getAttributes() == null ? null : item.getAttributes().get("findingCode");
            String code = raw == null ? "" : String.valueOf(raw).trim().toUpperCase();
            if (!code.isBlank()) {
                idsByCode.computeIfAbsent(code, ignored -> new ArrayList<>()).add(item.getEvidenceId());
            }
        }
        Set<String> codes = idsByCode.keySet();
        boolean metricAbnormal = containsAny(codes, "LATENCY_HIGH", "ERROR_RATE_HIGH", "SERVICE_DOWN",
                "RESOURCE_HIGH", "METRIC_ANOMALY");
        boolean logProblem = containsAny(codes, "DB_TIMEOUT", "CONNECTION_REFUSED", "OOM", "APP_ERROR");
        boolean normalConflict = (codes.contains("METRICS_NORMAL") && logProblem)
                || (codes.contains("LOGS_NORMAL") && metricAbnormal);
        boolean unrelatedChange = codes.contains("FRONTEND_CHANGE") && codes.contains("DB_TIMEOUT");
        List<String> conflictingIds = new ArrayList<>();
        if (normalConflict) {
            addIds(conflictingIds, idsByCode, "METRICS_NORMAL", "LOGS_NORMAL", "LATENCY_HIGH",
                    "ERROR_RATE_HIGH", "SERVICE_DOWN", "RESOURCE_HIGH", "METRIC_ANOMALY",
                    "DB_TIMEOUT", "CONNECTION_REFUSED", "OOM", "APP_ERROR");
        }
        if (unrelatedChange) {
            addIds(conflictingIds, idsByCode, "FRONTEND_CHANGE");
        }

        String hypothesisCode;
        String statement;
        if (normalConflict) {
            hypothesisCode = "evidence_conflict";
            statement = "指标与日志证据相互冲突，当前不能确认单一根因";
        } else if (metricAbnormal && codes.contains("DB_TIMEOUT")
                && containsAny(codes, "DATABASE_CHANGE", "SERVICE_DEPLOY")) {
            hypothesisCode = "database_change_regression";
            statement = "数据库超时与异常指标及近期后端变更在同一时间窗内相关";
        } else if (codes.contains("SERVICE_DOWN") && codes.contains("CONNECTION_REFUSED")) {
            hypothesisCode = "service_unavailable";
            statement = "服务不可用指标与连接拒绝日志相互印证";
        } else if (codes.contains("RESOURCE_HIGH") && codes.contains("OOM")) {
            hypothesisCode = "resource_exhaustion";
            statement = "资源异常指标与内存耗尽日志相互印证";
        } else if (metricAbnormal && codes.contains("DB_TIMEOUT")) {
            hypothesisCode = "database_dependency";
            statement = "异常指标与数据库超时日志相互印证，优先检查数据库依赖";
        } else if (metricAbnormal && codes.contains("APP_ERROR") && codes.contains("SERVICE_DEPLOY")) {
            hypothesisCode = "deployment_regression";
            statement = "应用错误与异常指标在近期服务发布后出现，优先核查发布回归";
        } else if (metricAbnormal && containsAny(codes, "DATABASE_CHANGE", "SERVICE_DEPLOY", "RECENT_CHANGE")) {
            hypothesisCode = "change_correlated_anomaly";
            statement = "异常指标与近期变更存在时序关联，仍需日志证据确认因果";
        } else if (metricAbnormal) {
            hypothesisCode = "metric_signal_unconfirmed";
            statement = "指标源发现异常信号，但缺少独立证据确认根因";
        } else if (logProblem) {
            hypothesisCode = "log_signal_unconfirmed";
            statement = "日志发现异常信号，但缺少指标或变更证据确认影响范围";
        } else {
            hypothesisCode = "recent_change_unconfirmed";
            statement = "时间窗内存在变更或调查线索，但不足以确认根因";
        }

        List<String> questions = new ArrayList<>();
        List<String> missing = distinct(missingEvidence);
        if (missing.stream().anyMatch(item -> item.contains("metrics"))) {
            questions.add("能否补充相同时间窗的 Prometheus 指标？");
        }
        if (missing.stream().anyMatch(item -> item.contains("log") || item.contains("trace"))) {
            questions.add("能否补充可关联的 traceId 或结构化错误日志？");
        }
        if (missing.stream().anyMatch(item -> item.contains("change"))) {
            questions.add("告警前后是否存在后端发布、配置或数据库变更？");
        }
        if (normalConflict || unrelatedChange) {
            questions.add("请确认各证据的服务、环境和时间窗口是否一致。");
        }
        if (sourceCount < 2 && questions.isEmpty()) {
            questions.add("请补充至少一个独立证据源以验证当前假设。");
        }
        String status = evidence.isEmpty() ? "INSUFFICIENT"
                : (degraded || sourceCount < 2 || normalConflict || unrelatedChange ? "PROVISIONAL" : "SUPPORTED");
        return new SignalAnalysis(hypothesisCode, statement, status,
                distinct(conflictingIds), distinct(questions));
    }

    private boolean containsAny(Set<String> values, String... candidates) {
        for (String candidate : candidates) {
            if (values.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void addIds(List<String> target,
                        Map<String, List<String>> idsByCode,
                        String... codes) {
        for (String code : codes) {
            target.addAll(idsByCode.getOrDefault(code, List.of()));
        }
    }

    private record SignalAnalysis(String hypothesisCode,
                                  String statement,
                                  String conclusionStatus,
                                  List<String> conflictingEvidenceIds,
                                  List<String> followUpQuestions) {
    }

    private List<String> distinct(List<String> values) {
        return values == null ? new ArrayList<>() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }
}
