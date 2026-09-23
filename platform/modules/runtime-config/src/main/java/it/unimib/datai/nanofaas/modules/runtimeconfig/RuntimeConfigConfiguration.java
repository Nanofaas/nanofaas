package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import it.unimib.datai.nanofaas.controlplane.capacity.AdmissionLimitsControl;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The {@code schedulerRuntimeConfigExtension} bean below is conditional on
 * {@link SchedulerControl}, and a {@code @ConditionalOnBean} on a {@code @Bean} method is decided
 * while its configuration class is being registered — so this auto-configuration must be processed
 * after the one that contributes the engine. That ordering used to hold only by accident
 * (auto-configurations otherwise sort by class name, and {@code …controlplane…} precedes
 * {@code …modules…}); it is declared here by name rather than by class, because this module
 * cannot depend on {@code :control-plane}, whose {@code SchedulerConfiguration} this names. Get
 * it wrong and the artifact does not fail to build: it simply serves no {@code scheduler}
 * namespace and 404s the whole admin switch API, which is what
 * {@code SchedulerStrategyConfigurationTest} pins.
 */
@AutoConfiguration
@AutoConfigureAfter(name = "it.unimib.datai.nanofaas.controlplane.service.SchedulerConfiguration")
public class RuntimeConfigConfiguration {

    @Bean
    RuntimeConfigExtension controlPlaneRuntimeConfigExtension(AdmissionLimitsControl limits) {
        return new ControlPlaneRuntimeConfigExtension(limits);
    }

    /**
     * Registered only when an engine exposing {@link SchedulerControl} is on the classpath and
     * composed as a bean (task 8's job); an artefact without it simply has no {@code scheduler}
     * namespace and GET/PATCH on it 404 like any other absent namespace.
     */
    @Bean
    @ConditionalOnBean(SchedulerControl.class)
    RuntimeConfigExtension schedulerRuntimeConfigExtension(SchedulerControl control) {
        return new SchedulerRuntimeConfigExtension(control);
    }

    @Bean
    RuntimeConfigRegistry runtimeConfigRegistry(java.util.List<RuntimeConfigExtension> extensions) {
        return new RuntimeConfigRegistry(extensions);
    }

    @Bean
    RuntimeConfigService runtimeConfigService(RuntimeConfigRegistry registry,
                                               MeterRegistry meterRegistry) {
        return new RuntimeConfigService(registry, meterRegistry);
    }

    /**
     * One worker, one queued request, reject anything past that. The admin PATCH path may block
     * until a namespace's change commits (the scheduler switch's linearization point), so this
     * bounds how much of that blocking work can pile up instead of opening an unbounded queue on
     * {@code boundedElastic} or blocking a Netty event-loop thread. Shut down by Spring on
     * context close; never a target for function-scheduling work.
     */
    @Bean(name = "adminRuntimeConfigExecutor", destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "nanofaas.admin.runtime-config.enabled", havingValue = "true")
    ThreadPoolExecutor adminRuntimeConfigExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().name("nanofaas-admin-config-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    @ConditionalOnProperty(name = "nanofaas.admin.runtime-config.enabled", havingValue = "true")
    AdminRuntimeConfigController adminRuntimeConfigController(RuntimeConfigService configService,
                                                               @Qualifier("adminRuntimeConfigExecutor") ThreadPoolExecutor adminRuntimeConfigExecutor) {
        return new AdminRuntimeConfigController(configService, adminRuntimeConfigExecutor);
    }
}
