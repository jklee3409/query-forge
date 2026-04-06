package io.queryforge.backend.admin.pipeline.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AdminPipelineConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService adminPipelineExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "admin-pipeline-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
