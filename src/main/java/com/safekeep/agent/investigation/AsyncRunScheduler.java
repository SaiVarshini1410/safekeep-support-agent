package com.safekeep.agent.investigation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class AsyncRunScheduler implements RunScheduler {

    private final ThreadPoolTaskExecutor executor;
    private final InvestigationRunner runner;

    public AsyncRunScheduler(@Qualifier("investigationExecutor") ThreadPoolTaskExecutor executor,
                             InvestigationRunner runner) {
        this.executor = executor;
        this.runner = runner;
    }

    @Override
    public void submit(Long investigationId) {
        executor.execute(() -> runner.run(investigationId));
    }

    @Override
    public int activeCount() { return executor.getActiveCount(); }

    @Override
    public int queueSize() { return executor.getThreadPoolExecutor().getQueue().size(); }
}
