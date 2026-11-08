package com.safekeep.agent.config;

import com.safekeep.agent.apps.ApplicationRegistry;
import com.safekeep.agent.common.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration
public class CoreConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Json json() {
        return new Json();
    }

    @Bean
    public ApplicationRegistry applicationRegistry(SafekeepProperties props) {
        return new ApplicationRegistry(props.applications(), props.apiKeys());
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    /** Bounded worker pool for investigations (Spec Section 4.6). */
    @Bean(name = "investigationExecutor")
    public ThreadPoolTaskExecutor investigationExecutor(SafekeepProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(props.agent().workerThreads());
        ex.setMaxPoolSize(props.agent().workerThreads());
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("investigation-");
        ex.initialize();
        return ex;
    }
}
