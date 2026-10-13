package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiopsDiagnosisQualityEvalTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void evaluateFrozenDatasets() throws Exception {
        String configured = System.getProperty("aiops.quality.datasets", "");
        Assumptions.assumeTrue(!configured.isBlank(),
                "set -Daiops.quality.datasets to run the quality evaluation");
        Path outputDir = Path.of(System.getProperty("aiops.quality.outputDir", "eval/reports"));
        int rounds = Math.max(3, Integer.getInteger("aiops.quality.rounds", 3));
        Files.createDirectories(outputDir);

        for (String item : configured.split(";")) {
            Dataset dataset = objectMapper.readValue(Path.of(item.trim()).toFile(), Dataset.class);
            Report report = evaluate(dataset, rounds);
            Path json = outputDir.resolve("aiops_diagnosis_quality_" + dataset.name + "_report.json");
            Path markdown = outputDir.resolve("aiops_diagnosis_quality_" + dataset.name + "_report.md");
            objectMapper.writeValue(json.toFile(), report);
            Files.writeString(markdown, markdown(report));
            assertTrue(report.passed, () -> "quality gates failed for " + dataset.name + ": " + report.failures);
        }
    }

    private Report evaluate(Dataset dataset, int rounds) {
        List<CaseResult> results = new ArrayList<>();
        List<Double> parallelLatencies = new ArrayList<>();
        List<Double> serialLatencies = new ArrayList<>();
        for (CaseDefinition definition : dataset.cases) {
            Diagnosis parallelDiagnosis = null;
            Diagnosis serialDiagnosis = null;
            List<Double> caseParallel = new ArrayList<>();
            List<Double> caseSerial = new ArrayList<>();
            for (int round = 0; round < rounds; round++) {
                if (round % 2 == 0) {
                    TimedDiagnosis parallel = parallel(definition);
                    TimedDiagnosis serial = serial(definition);
                    parallelDiagnosis = parallel.diagnosis;
                    serialDiagnosis = serial.diagnosis;
                    caseParallel.add(parallel.elapsedMs);
                    caseSerial.add(serial.elapsedMs);
                } else {
                    TimedDiagnosis serial = serial(definition);
                    TimedDiagnosis parallel = parallel(definition);
                    parallelDiagnosis = parallel.diagnosis;
                    serialDiagnosis = serial.diagnosis;
                    caseParallel.add(parallel.elapsedMs);
                    caseSerial.add(serial.elapsedMs);
                }
            }
            parallelLatencies.addAll(caseParallel);
            serialLatencies.addAll(caseSerial);
            results.add(score(definition, parallelDiagnosis, serialDiagnosis,
                    percentile(caseParallel, 0.50), percentile(caseSerial, 0.50)));
        }

        double parallelRoot = ratio(results, item -> item.parallelRootCorrect);
        double serialRoot = ratio(results, item -> item.serialRootCorrect);
        double evidenceSupport = ratio(results, item -> item.evidenceSupported);
        double citationExistence = ratio(results, item -> item.citationIdsExist);
        double deferral = ratio(results, item -> item.deferralCorrect);
        List<String> failures = new ArrayList<>();
        if (parallelRoot < 0.85) failures.add("rootCauseAccuracy<0.85");
        if (evidenceSupport < 0.90) failures.add("evidenceSupportRate<0.90");
        if (citationExistence < 1.0) failures.add("citationExistenceRate<1.0");
        if (deferral < 1.0) failures.add("deferralAccuracy<1.0");
        if (serialRoot - parallelRoot > 0.02) failures.add("parallelQualityDrop>0.02");

        Report report = new Report();
        report.requirementId = "QUALITY-AC-002";
        report.dataset = dataset.name;
        report.datasetRole = dataset.role;
        report.caseCount = results.size();
        report.roundsPerMode = rounds;
        report.supervisorMode = "DETERMINISTIC_STRUCTURED";
        report.modelId = "none";
        report.actualTokenUsage = 0L;
        report.parallelRootCauseAccuracy = parallelRoot;
        report.serialRootCauseAccuracy = serialRoot;
        report.evidenceSupportRate = evidenceSupport;
        report.citationIdExistenceRate = citationExistence;
        report.deferralAccuracy = deferral;
        report.parallelP95Ms = percentile(parallelLatencies, 0.95);
        report.serialP95Ms = percentile(serialLatencies, 0.95);
        report.failures = failures;
        report.passed = failures.isEmpty();
        report.results = results;
        return report;
    }

    private TimedDiagnosis parallel(CaseDefinition definition) {
        List<AiopsSpecialistAgent> agents = definition.specialists.stream()
                .map(this::agent)
                .toList();
        BuiltinSupervisorCollaborationEngine engine = new BuiltinSupervisorCollaborationEngine(
                agents,
                new AiopsDiagnosisSupervisor(),
                new DefaultAiopsRemediationPolicy(),
                new SimpleMeterRegistry(),
                1_000L,
                3);
        long started = System.nanoTime();
        try {
            Diagnosis diagnosis = engine.investigate(context(definition)).getDiagnosis();
            return new TimedDiagnosis(diagnosis, elapsed(started));
        } finally {
            engine.shutdown();
        }
    }

    private TimedDiagnosis serial(CaseDefinition definition) {
        List<SpecialistResult> specialistResults = new ArrayList<>();
        long started = System.nanoTime();
        AlertInvestigationContext context = context(definition);
        definition.specialists.stream()
                .sorted(Comparator.comparing(item -> item.type))
                .map(this::agent)
                .forEach(agent -> specialistResults.add(agent.investigate(context)));
        Diagnosis diagnosis = new AiopsDiagnosisSupervisor().diagnose(context, specialistResults);
        return new TimedDiagnosis(diagnosis, elapsed(started));
    }

    private AiopsSpecialistAgent agent(SpecialistDefinition definition) {
        SpecialistType type = SpecialistType.valueOf(definition.type);
        return new AiopsSpecialistAgent() {
            @Override
            public SpecialistType type() {
                return type;
            }

            @Override
            public SpecialistResult investigate(AlertInvestigationContext context) {
                sleep(definition.latencyMs);
                if ("TIMEOUT".equals(definition.status)) {
                    return SpecialistResult.timeout(type);
                }
                if (!"COMPLETED".equals(definition.status)) {
                    return SpecialistResult.degraded(type,
                            definition.missing == null ? type.name().toLowerCase() + "_result" : definition.missing,
                            "synthetic unavailable evidence source");
                }
                Evidence evidence = Evidence.observed(
                        context.getEventId() + "-" + type.name().toLowerCase(),
                        type,
                        type.name().toLowerCase(),
                        context.getService(),
                        definition.findingCode,
                        context.effectiveObservedAt(),
                        Map.of("findingCode", definition.findingCode));
                return SpecialistResult.completed(type, List.of(evidence));
            }
        };
    }

    private CaseResult score(CaseDefinition definition,
                             Diagnosis parallel,
                             Diagnosis serial,
                             double parallelMedian,
                             double serialMedian) {
        CaseResult result = new CaseResult();
        result.caseId = definition.caseId;
        result.category = definition.category;
        result.parallelHypothesis = hypothesisId(parallel);
        result.serialHypothesis = hypothesisId(serial);
        result.parallelConclusion = parallel.getConclusionStatus();
        result.serialConclusion = serial.getConclusionStatus();
        result.parallelRootCorrect = definition.acceptableHypotheses.contains(result.parallelHypothesis);
        result.serialRootCorrect = definition.acceptableHypotheses.contains(result.serialHypothesis);
        Set<String> evidenceIds = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        parallel.getEvidence().forEach(item -> {
            evidenceIds.add(item.getEvidenceId());
            if (item.getSource() != null) sources.add(item.getSource().name());
        });
        result.citationIdsExist = parallel.getRootCauseHypotheses().stream()
                .flatMap(item -> item.getEvidenceIds().stream())
                .allMatch(evidenceIds::contains);
        result.evidenceSupported = result.citationIdsExist && sources.containsAll(definition.requiredSources);
        result.deferralCorrect = !definition.requireDeferral
                || !"SUPPORTED".equals(parallel.getConclusionStatus());
        result.followUpPresent = !definition.requireFollowUp
                || (parallel.getFollowUpQuestions() != null && !parallel.getFollowUpQuestions().isEmpty());
        result.parallelMedianMs = parallelMedian;
        result.serialMedianMs = serialMedian;
        result.passed = result.parallelRootCorrect && result.serialRootCorrect
                && result.evidenceSupported && result.citationIdsExist
                && result.deferralCorrect && result.followUpPresent;
        return result;
    }

    private AlertInvestigationContext context(CaseDefinition definition) {
        return AlertInvestigationContext.builder()
                .eventId(definition.caseId)
                .alertName(definition.alertName)
                .service("marketplace-api")
                .traceId("trace-" + definition.caseId.toLowerCase())
                .observedAt(Instant.parse("2026-09-21T00:00:00Z"))
                .build();
    }

    private String hypothesisId(Diagnosis diagnosis) {
        return diagnosis.getRootCauseHypotheses() == null || diagnosis.getRootCauseHypotheses().isEmpty()
                ? "" : diagnosis.getRootCauseHypotheses().get(0).getHypothesisId();
    }

    private double ratio(List<CaseResult> results, java.util.function.Predicate<CaseResult> predicate) {
        return results.isEmpty() ? 0D : results.stream().filter(predicate).count() / (double) results.size();
    }

    private double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) return 0D;
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private double elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started) / 1000D;
    }

    private void sleep(long latencyMs) {
        try {
            Thread.sleep(Math.max(0L, latencyMs));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String markdown(Report report) {
        return "# AIOps Diagnosis Quality Report\n\n"
                + "- requirementId: " + report.requirementId + "\n"
                + "- dataset: " + report.dataset + " (" + report.datasetRole + ")\n"
                + "- cases: " + report.caseCount + "\n"
                + "- roundsPerMode: " + report.roundsPerMode + "\n"
                + "- supervisorMode: " + report.supervisorMode + "\n"
                + "- actualTokenUsage: " + report.actualTokenUsage + "\n"
                + "- parallelRootCauseAccuracy: " + report.parallelRootCauseAccuracy + "\n"
                + "- serialRootCauseAccuracy: " + report.serialRootCauseAccuracy + "\n"
                + "- evidenceSupportRate: " + report.evidenceSupportRate + "\n"
                + "- citationIdExistenceRate: " + report.citationIdExistenceRate + "\n"
                + "- deferralAccuracy: " + report.deferralAccuracy + "\n"
                + "- parallelP95Ms: " + report.parallelP95Ms + "\n"
                + "- serialP95Ms: " + report.serialP95Ms + "\n"
                + "- passed: " + report.passed + "\n"
                + "- failures: " + report.failures + "\n";
    }

    private record TimedDiagnosis(Diagnosis diagnosis, double elapsedMs) {
    }

    public static class Dataset {
        public String name;
        public String role;
        public List<CaseDefinition> cases = new ArrayList<>();
    }

    public static class CaseDefinition {
        public String caseId;
        public String category;
        public String alertName;
        public List<SpecialistDefinition> specialists = new ArrayList<>();
        public List<String> acceptableHypotheses = new ArrayList<>();
        public Set<String> requiredSources = new LinkedHashSet<>();
        public boolean requireDeferral;
        public boolean requireFollowUp;
    }

    public static class SpecialistDefinition {
        public String type;
        public String status = "COMPLETED";
        public String findingCode;
        public String missing;
        public long latencyMs;
    }

    public static class CaseResult {
        public String caseId;
        public String category;
        public String parallelHypothesis;
        public String serialHypothesis;
        public String parallelConclusion;
        public String serialConclusion;
        public boolean parallelRootCorrect;
        public boolean serialRootCorrect;
        public boolean evidenceSupported;
        public boolean citationIdsExist;
        public boolean deferralCorrect;
        public boolean followUpPresent;
        public double parallelMedianMs;
        public double serialMedianMs;
        public boolean passed;
    }

    public static class Report {
        public String requirementId;
        public String dataset;
        public String datasetRole;
        public int caseCount;
        public int roundsPerMode;
        public String supervisorMode;
        public String modelId;
        public long actualTokenUsage;
        public double parallelRootCauseAccuracy;
        public double serialRootCauseAccuracy;
        public double evidenceSupportRate;
        public double citationIdExistenceRate;
        public double deferralAccuracy;
        public double parallelP95Ms;
        public double serialP95Ms;
        public boolean passed;
        public List<String> failures = new ArrayList<>();
        public List<CaseResult> results = new ArrayList<>();
    }
}
