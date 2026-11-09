package com.safekeep.agent.investigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs investigations on a bounded worker pool (Spec Section 4.6).
 * If the pool and its queue are full, the investigation stays RECEIVED and is retried every 15 s.
 */
@Component
public class AsyncRunScheduler implements RunScheduler {

    private static final Logger log = LoggerFactory.getLogger(AsyncRunScheduler.class);

    private final ThreadPoolTaskExecutor executor;
    private final InvestigationRunner runner;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    public AsyncRunScheduler(@Qualifier("investigationExecutor") ThreadPoolTaskExecutor executor,
                             InvestigationRunner runner) {
        this.executor = executor;
        this.runner = runner;
    }

    @Override
    public void submit(Long investigationId) {
        try {
            executor.execute(() -> runner.run(investigationId));
        } catch (TaskRejectedException e) {
            pending.add(investigationId);
            log.warn("Worker queue full; investigation {} will be retried", investigationId);
        }
    }

    /** Retries work that was rejected because the queue was full. */
    @Scheduled(fixedDelay = 15_000)
    public void retryPending() {
        for (Long id : Set.copyOf(pending)) {
            try {
                executor.execute(() -> runner.run(id));
                pending.remove(id);
            } catch (TaskRejectedException e) {
                return;   // still full; try again next time
            }
        }
    }

    public int pendingCount() { return pending.size(); }

    @Override
    public int activeCount() { return executor.getActiveCount(); }

    @Override
    public int queueSize() { return executor.getThreadPoolExecutor().getQueue().size() + pending.size(); }
}
