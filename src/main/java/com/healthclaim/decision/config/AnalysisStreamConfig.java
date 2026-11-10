package com.healthclaim.decision.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

/**
 * Executor for streamed analyses. The request thread returns the SSE emitter
 * immediately while the analysis runs here, so the MDC (trace id, which the audit
 * trail records) and the caller's security context are copied onto the worker.
 */
@Configuration
public class AnalysisStreamConfig {

    public static final String EXECUTOR = "analysisStreamExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor analysisStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64); // beyond this, submissions are rejected (caller gets an error event)
        executor.setThreadNamePrefix("analysis-stream-");
        executor.setTaskDecorator(contextPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    static TaskDecorator contextPropagatingDecorator() {
        return task -> {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            SecurityContext security = SecurityContextHolder.getContext();
            return () -> {
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                SecurityContext previousSecurity = SecurityContextHolder.getContext();
                try {
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    }
                    SecurityContextHolder.setContext(security);
                    task.run();
                } finally {
                    if (previousMdc != null) {
                        MDC.setContextMap(previousMdc);
                    } else {
                        MDC.clear();
                    }
                    SecurityContextHolder.setContext(previousSecurity);
                }
            };
        };
    }
}
