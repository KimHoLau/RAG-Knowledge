package com.ragknowledge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 入库专用线程池。
 *
 * <p>入库（Tika 解析 + 批量向量化）是耗时的 CPU/IO 混合任务，如果沿用 Spring Boot 默认的任务执行器，
 * 会与 Spring MVC 的异步请求处理争抢同一批线程，多文档并发上传时互相拖累。这里单独给一个命名线程池，
 * 由 {@code @Async("ragIngestExecutor")} 显式引用。
 *
 * <p>队列满时用 CallerRunsPolicy：让上传请求线程自己执行入库，形成天然背压，
 * 宁可这次上传变慢，也不静默丢弃任务（丢弃会导致文档永远卡在 PROCESSING）。
 */
@Configuration
public class AsyncConfig {

    @Bean("ragIngestExecutor")
    public Executor ragIngestExecutor(
            @Value("${rag.ingest.core-pool-size}") int corePoolSize,
            @Value("${rag.ingest.max-pool-size}") int maxPoolSize,
            @Value("${rag.ingest.queue-capacity}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("rag-ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 关闭时等待在途入库写完状态，避免文档停在 PROCESSING
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
