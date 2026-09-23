package com.pgy.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置。
 *
 * 【为什么任务要丢到线程池里跑？】
 * 生成一篇文章要十几秒。如果直接在 HTTP 请求线程里等，
 * 这个线程就被占住了，别的请求进不来。用户量一上来就会排队卡死。
 *
 * 丢到后台线程池后：接口立刻返回"任务已受理，编号 xxx"，
 * HTTP 线程马上释放去接下一个请求，用户拿着编号轮询进度即可。
 */
@Slf4j
@Configuration
public class AsyncConfig {

    /**
     * 网关通用线程池，负责跑流式转发和后台任务。
     */
    @Bean("gatewayTaskExecutor")
    public TaskExecutor gatewayTaskExecutor(GatewayProperties properties) {
        int poolSize = Math.max(2, properties.getTask().getWorkerPoolSize());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize * 2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("gw-worker-");

        // 队列也满了的时候，由提交任务的线程自己执行。
        // 这相当于一种"背压"：宁可让调用方慢一点，也不要把任务直接丢掉。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        log.info("任务线程池已初始化，核心线程数 {}", poolSize);
        return executor;
    }
}
