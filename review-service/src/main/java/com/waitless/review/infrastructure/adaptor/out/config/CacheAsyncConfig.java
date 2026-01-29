package com.waitless.review.infrastructure.adaptor.out.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class CacheAsyncConfig {

    public static final String CACHE_SAVE_EXECUTOR = "cacheSaveExecutor";

    @Bean(name = CACHE_SAVE_EXECUTOR)
    public Executor cacheSaveExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);  // 200 → 500 (역류 확률 감소)
        executor.setThreadNamePrefix("cache-save-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());  // CallerRunsPolicy → DiscardOldestPolicy (역류 방지)
        executor.initialize();
        return executor;
    }
}
