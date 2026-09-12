package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.config.ReplicaStatusSnapshotConfiguration;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionOperationLocks;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The beans a managed deployment needs: the replica snapshot and its two refresh pools, the
 * wake-up executor, its timeout scheduler, the wake-up coordinator, the deployment coordinator and
 * the wake-up gate that is the dispatch path's readiness.
 *
 * <p>Every one of these owns threads or in-flight state, and none of them can do any work without
 * a managed provider. The condition that decides whether they exist at all lives on
 * {@link ManagedDeploymentOrchestrationAutoConfiguration}, which imports this class; keeping the
 * definitions separate from the condition lets a test register them directly and assert their
 * ownership without having to satisfy the condition first.</p>
 *
 * <p>These live in this package rather than in {@code deployment} because the wake-up gate is a
 * service-layer class and {@code deployment} may not depend on {@code service} — nor could the gate
 * move down, since it reads the function catalog and {@code registry} already depends on
 * {@code deployment}. This placement is what keeps the package graph acyclic.</p>
 */
@Configuration(proxyBeanMethods = false)
@Import(ReplicaStatusSnapshotConfiguration.class)
public class ManagedDeploymentOrchestration {





    @Bean
    public ManagedDeploymentCoordinator managedDeploymentCoordinator(DeploymentProviderResolver resolver,
                                                                     FunctionRegistry registry,
                                                                     FunctionOperationLocks locks,
                                                                     FunctionCapacityRegistry generations,
                                                                     ReplicaStatusSnapshot snapshot) {
        return new ManagedDeploymentCoordinator(resolver, registry, locks, generations, snapshot);
    }

    @Bean("deploymentWakeUpExecutor")
    public ThreadPoolTaskExecutor deploymentWakeUpExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("deployment-wakeup-");
        return executor;
    }

    @Bean(name = "deploymentWakeUpTimeoutScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService deploymentWakeUpTimeoutScheduler() {
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

    @Bean(destroyMethod = "close")
    public DeploymentWakeUpCoordinator deploymentWakeUpCoordinator(
            FunctionCapacityRegistry generations,
            ScheduledExecutorService deploymentWakeUpTimeoutScheduler) {
        return new DeploymentWakeUpCoordinator(generations, deploymentWakeUpTimeoutScheduler);
    }

    /**
     * Also the dispatch path's {@code DeploymentReadiness}: the gate implements it, so there is one
     * bean rather than a second one delegating to it.
     */
    @Bean(destroyMethod = "close")
    public DeploymentWakeUpGate deploymentWakeUpGate(FunctionRegistry registry,
                                                     ManagedDeploymentCoordinator coordinator,
                                                     FunctionCapacityRegistry generations,
                                                     DeploymentWakeUpProperties properties,
                                                     ThreadPoolTaskExecutor deploymentWakeUpExecutor,
                                                     ScheduledExecutorService deploymentWakeUpTimeoutScheduler,
                                                     DeploymentWakeUpCoordinator wakeUpCoordinator) {
        return new DeploymentWakeUpGate(registry, coordinator, generations, properties,
                deploymentWakeUpExecutor, deploymentWakeUpTimeoutScheduler, wakeUpCoordinator);
    }

}
