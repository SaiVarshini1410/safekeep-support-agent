package com.safekeep.agent.investigation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncRunSchedulerTest {

    @Test
    @DisplayName("TC-INV-07 Work rejected by a full queue is kept and retried later")
    void fullQueueIsRetried() throws Exception {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(0);
        ex.initialize();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);
        List<Long> ran = new CopyOnWriteArrayList<>();
        AsyncRunScheduler scheduler = new AsyncRunScheduler(ex, id -> {
            ran.add(id);
            if (id == 1L) {
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } else {
                secondRan.countDown();
            }
        });

        scheduler.submit(1L);           // occupies the only worker
        Thread.sleep(100);
        scheduler.submit(2L);           // rejected: no free worker, no queue
        assertThat(scheduler.pendingCount()).isEqualTo(1);

        release.countDown();            // worker becomes free
        Thread.sleep(200);
        scheduler.retryPending();
        assertThat(secondRan.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(ran).containsExactly(1L, 2L);
        ex.shutdown();
    }
}
