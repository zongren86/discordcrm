package com.discordadmin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置 (针对 4C/8G 生产环境 + local dev):
 *
 * ┌──────────────────┬──────────────┬──────────────────────────────────────────┐
 * │ Bean             │ 4C/8G Prod   │ 说明                                     │
 * ├──────────────────┼──────────────┼──────────────────────────────────────────┤
 * │ scheduler        │ 4 线程       │ @Scheduled 定时任务 (定时多, 线程够了)    │
 * │ asyncExecutor    │ core=4/max=16│ @Async 异步 (翻译/ASR 等轻量)            │
 * │ pollExecutor     │ core=8/max=24│ 消息轮询 (IO密集, 但4C8G别开太大)        │
 * └──────────────────┴──────────────┴──────────────────────────────────────────┘
 *
 * 线程数公式 (生产 4C):
 *   IO密集型线程 ≈ 2C × 4 = 16 (pollExecutor 取 core=8/max=24)
 *   CPU密集型线程 ≈ C + 1 = 5  (scheduler 取 4)
 *   不要用 ForkJoinPool.commonPool() — 被 Tomcat 占满时 @Scheduled 会饿
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer, SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(scheduler());
    }

    /** 调度线程池 — @Scheduled 定时任务 */
    @Bean
    public ThreadPoolTaskScheduler scheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Override
    public Executor getAsyncExecutor() {
        return asyncExecutor();
    }

    /** 通用异步线程池 — @Async */
    @Bean(name = "asyncExecutor")
    public ThreadPoolTaskExecutor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 消息轮询线程池 (IO密集):
     *   生产 4C/8G: core=8, max=24, queue=500
     *   太多线程反而增加上下文切换开销, 24 足够了
     */
    @Bean(name = "pollExecutor")
    public ThreadPoolTaskExecutor pollExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(24);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("poll-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
