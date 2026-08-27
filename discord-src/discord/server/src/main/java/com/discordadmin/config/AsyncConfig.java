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
 * 线程池配置 — 目标环境 4C/8G:
 * - scheduler: @Scheduled 定时任务，10+ 个任务中含 1s/5s/30s 高频，12 线程
 * - asyncExecutor: @Async 翻译/ASR，IO 密集，core=max 避免线程切换
 * - pollExecutor: Discord DM 轮询，IO 密集，core=max
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer, SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(scheduler());
    }

    /**
     * 调度线程池：12 线程
     * 定时任务清单（共享此池）：
     *   - 1s  AutoAddService（加好友循环）
     *   - 5s  UserMessagePoller（DM 轮询）
     *   - 30s EmuInstanceService + CloudWebSocket + PhysicalSync（3 个）
     *   - 60s AutoAddTaskService 检查
     *   - 300s AutoAddTaskService 超时检查
     *   - 600s PresenceSync + RelationshipSync + TokenCheck（3 个）
     */
    @Bean
    public ThreadPoolTaskScheduler scheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(12);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Override
    public Executor getAsyncExecutor() {
        return asyncExecutor();
    }

    /** 异步线程池：翻译 / ASR，IO 密集型 core=max */
    @Bean(name = "asyncExecutor")
    public ThreadPoolTaskExecutor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** 消息轮询线程池：IO 密集，core=max */
    @Bean(name = "pollExecutor")
    public ThreadPoolTaskExecutor pollExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(40);
        executor.setMaxPoolSize(40);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("poll-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
