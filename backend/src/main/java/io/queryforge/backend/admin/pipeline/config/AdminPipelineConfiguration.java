package io.queryforge.backend.admin.pipeline.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AdminPipelineConfiguration {

    @Primary
    @Bean(name = "adminPipelineExecutor", destroyMethod = "shutdown")
    public ExecutorService adminPipelineExecutor() {
        return Executors.newSingleThreadExecutor(namedThreadFactory("admin-pipeline-worker"));
    }

    @Bean(name = "llmJobExecutor", destroyMethod = "shutdown")
    public ExecutorService llmJobExecutor() {
        return Executors.newFixedThreadPool(3, namedThreadFactory("llm-job-worker"));
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
