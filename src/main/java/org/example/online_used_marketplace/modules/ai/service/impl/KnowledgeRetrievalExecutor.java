package org.example.online_used_marketplace.modules.ai.service.impl;

import jakarta.annotation.PreDestroy;
import org.example.online_used_marketplace.modules.ai.config.KnowledgeIngestionProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bounded executor for independent vector and keyword retrieval channels.
 */
@Component
public class KnowledgeRetrievalExecutor {

    private final ExecutorService executor;

    public KnowledgeRetrievalExecutor(KnowledgeIngestionProperties properties) {
        int parallelism = Math.max(2, properties.getRetrieval().getParallelism());
        int queueCapacity = Math.max(parallelism, properties.getRetrieval().getParallelQueueCapacity());
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "knowledge-retrieval-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public <T> CompletableFuture<T> submit(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
