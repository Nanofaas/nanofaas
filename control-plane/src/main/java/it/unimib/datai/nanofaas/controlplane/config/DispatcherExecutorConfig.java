package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class DispatcherExecutorConfig {
    @Bean(name = "k8sDispatcherExecutor", destroyMethod = "shutdown")
    public ExecutorService k8sDispatcherExecutor(KubernetesProperties properties) {
        int threads = properties.apiThreadsOrDefault();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "k8s-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }
}
