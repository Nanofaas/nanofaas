package it.unimib.datai.nanofaas.controlplane.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
class DeploymentWakeUpConfiguration {

    @Bean("deploymentWakeUpExecutor")
    ThreadPoolTaskExecutor deploymentWakeUpExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("deployment-wakeup-");
        return executor;
    }

    @Bean(name = "deploymentWakeUpTimeoutScheduler", destroyMethod = "shutdown")
    ScheduledExecutorService deploymentWakeUpTimeoutScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "deployment-wakeup-timeout-");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
