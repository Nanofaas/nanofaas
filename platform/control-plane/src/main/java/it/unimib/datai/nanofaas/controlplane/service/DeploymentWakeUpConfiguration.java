package it.unimib.datai.nanofaas.controlplane.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "deployment-wakeup-timeout-");
            thread.setDaemon(true);
            return thread;
        });
    }
}
