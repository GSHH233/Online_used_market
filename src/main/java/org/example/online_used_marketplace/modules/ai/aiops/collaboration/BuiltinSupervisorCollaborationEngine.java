package org.example.online_used_marketplace.modules.ai.aiops.collaboration;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class BuiltinSupervisorCollaborationEngine implements AiopsCollaborationEngine {

    private final List<AiopsSpecialistAgent> specialists;
    private final AiopsDiagnosisSupervisor supervisor;
    private final AiopsRemediationPolicy remediationPolicy;
    private final MeterRegistry meterRegistry;
    private final long specialistTimeoutMs;
    private final ThreadPoolExecutor executor;

    @Autowired
    public BuiltinSupervisorCollaborationEngine(
            List<AiopsSpecialistAgent> specialists,
            AiopsDiagnosisSupervisor supervisor,
            AiopsRemediationPolicy remediationPolicy,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            @Value("${ai.aiops.collaboration.specialist-timeout-ms:3500}") long specialistTimeoutMs,
            @Value("${ai.aiops.collaboration.max-parallelism:3}") int maxParallelism) {
        this(specialists, supervisor, remediationPolicy,
                meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable(),
                specialistTimeoutMs, maxParallelism);
    }

    public BuiltinSupervisorCollaborationEngine(List<AiopsSpecialistAgent> specialists,
                                                AiopsDiagnosisSupervisor supervisor,
                                                AiopsRemediationPolicy remediationPolicy,
                                                MeterRegistry meterRegistry) {
        this(specialists, supervisor, remediationPolicy, meterRegistry, 3500L, 3);
    }

    public BuiltinSupervisorCollaborationEngine(List<AiopsSpecialistAgent> specialists,
                                                AiopsDiagnosisSupervisor supervisor,
                                                AiopsRemediationPolicy remediationPolicy,
                                                MeterRegistry meterRegistry,
                                                long specialistTimeoutMs) {
        this(specialists, supervisor, remediationPolicy, meterRegistry, specialistTimeoutMs, 3);
    }

    public BuiltinSupervisorCollaborationEngine(List<AiopsSpecialistAgent> specialists,
                                                AiopsDiagnosisSupervisor supervisor,
                                                AiopsRemediationPolicy remediationPolicy,
                                                MeterRegistry meterRegistry,
                                                long specialistTimeoutMs,
                                                int maxParallelism) {
        this.specialists = specialists == null ? List.of() : specialists.stream()
                .filter(agent -> agent != null && agent.type() != null)
                .sorted(Comparator.comparing(agent -> agent.type().name()))
                .toList();
        this.supervisor = supervisor == null ? new AiopsDiagnosisSupervisor() : supervisor;
        this.remediationPolicy = remediationPolicy == null
                ? new DefaultAiopsRemediationPolicy()
                : remediationPolicy;
        this.meterRegistry = meterRegistry;
        this.specialistTimeoutMs = Math.max(1L, specialistTimeoutMs);
        int parallelism = Math.max(1, maxParallelism);
        this.executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, parallelism * 2)),
                new CollaborationThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public AiopsCollaborationResult investigate(AlertInvestigationContext context) {
        long startedAt = System.nanoTime();
        AlertInvestigationContext safeContext = context == null
                ? AlertInvestigationContext.builder().build()
                : context;
        safeContext.normalizeExternalAnnotations();
        Map<SpecialistType, SpecialistResult> results = new EnumMap<>(SpecialistType.class);
        Map<Future<SpecialistResult>, SpecialistType> pending = new LinkedHashMap<>();
        CompletionService<SpecialistResult> completion = new ExecutorCompletionService<>(executor);

        for (AiopsSpecialistAgent specialist : specialists) {
            try {
                Future<SpecialistResult> future = completion.submit(specialistTask(specialist, safeContext));
                pending.put(future, specialist.type());
            } catch (RejectedExecutionException ex) {
                results.put(specialist.type(), SpecialistResult.degraded(
                        specialist.type(), "executor_rejected", "specialist queue is full"));
                recordDegraded(specialist.type(), "rejected");
            }
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(specialistTimeoutMs);
        while (!pending.isEmpty()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            Future<SpecialistResult> completed;
            try {
                completed = completion.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            if (completed == null) {
                break;
            }
            SpecialistType type = pending.remove(completed);
            if (type == null) {
                continue;
            }
            try {
                SpecialistResult result = completed.get();
                if (result == null) {
                    result = SpecialistResult.degraded(type, "empty_result", "specialist returned no result");
                }
                result.setSpecialist(type);
                results.put(type, result);
                recordSpecialistMetrics(type, result);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                results.put(type, SpecialistResult.degraded(type, "interrupted", "specialist interrupted"));
                recordDegraded(type, "interrupted");
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                results.put(type, SpecialistResult.degraded(type, "execution_failure", safeMessage(cause)));
                recordDegraded(type, "failure");
            }
        }

        for (Map.Entry<Future<SpecialistResult>, SpecialistType> entry : pending.entrySet()) {
            entry.getKey().cancel(true);
            SpecialistType type = entry.getValue();
            results.put(type, SpecialistResult.timeout(type));
            recordDegraded(type, "timeout");
            recordSpecialistMetrics(type, results.get(type));
        }

        List<SpecialistResult> orderedResults = new ArrayList<>();
        EnumSet<SpecialistType> seen = EnumSet.noneOf(SpecialistType.class);
        for (AiopsSpecialistAgent specialist : specialists) {
            SpecialistType type = specialist.type();
            SpecialistResult result = results.get(type);
            if (result != null && seen.add(type)) {
                orderedResults.add(result);
            }
        }
        Diagnosis diagnosis = supervisor.diagnose(safeContext, orderedResults);
        RemediationRecommendation remediation = remediationPolicy.recommend(safeContext, diagnosis);
        diagnosis.setRecommendedActionCode(remediation.getActionCode());
        diagnosis.setRecommendedParams(new LinkedHashMap<>(remediation.getParams()));
        diagnosis.setRecommendationReason(remediation.getReason());
        recordDiagnosisMetrics(diagnosis);

        List<SpecialistType> degraded = orderedResults.stream()
                .filter(result -> result.getStatus() != SpecialistStatus.COMPLETED)
                .map(SpecialistResult::getSpecialist)
                .toList();
        return AiopsCollaborationResult.builder()
                .context(safeContext)
                .specialistResults(orderedResults)
                .diagnosis(diagnosis)
                .remediation(remediation)
                .degradedSpecialists(new ArrayList<>(degraded))
                .elapsedMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
                .build();
    }

    private Callable<SpecialistResult> specialistTask(AiopsSpecialistAgent specialist,
                                                       AlertInvestigationContext context) {
        return () -> {
            long startedAt = System.nanoTime();
            SpecialistResult result = specialist.investigate(context);
            if (result == null) {
                result = SpecialistResult.degraded(specialist.type(), "empty_result", "specialist returned no result");
            }
            result.setSpecialist(specialist.type());
            result.setElapsedMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            return result;
        };
    }

    private void recordSpecialistMetrics(SpecialistType type, SpecialistResult result) {
        if (meterRegistry == null || result == null) {
            return;
        }
        Timer.builder("aiops.collaboration.specialist.duration")
                .tag("specialist", type.name().toLowerCase())
                .tag("status", result.getStatus().name().toLowerCase())
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0L, result.getElapsedMs())));
    }

    private void recordDegraded(SpecialistType type, String reason) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("aiops.collaboration.specialist.degraded",
                        "specialist", type.name().toLowerCase(), "reason", reason)
                .increment();
    }

    private void recordDiagnosisMetrics(Diagnosis diagnosis) {
        if (meterRegistry == null || diagnosis == null) {
            return;
        }
        DistributionSummary.builder("aiops.collaboration.diagnosis.confidence")
                .description("Deterministic confidence emitted by the AIOps collaboration supervisor")
                .register(meterRegistry)
                .record(Math.max(0D, Math.min(1D, diagnosis.getConfidence())));
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return throwable == null ? "unknown specialist failure" : throwable.getClass().getSimpleName();
        }
        return throwable.getMessage();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class CollaborationThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "aiops-collaboration-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
