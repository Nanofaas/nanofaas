package it.unimib.datai.nanofaas.controlplane.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
}
