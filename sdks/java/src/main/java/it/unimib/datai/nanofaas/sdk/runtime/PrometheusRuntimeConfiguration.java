package it.unimib.datai.nanofaas.sdk.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Ensure Prometheus is available out-of-the-box for function containers.
 *
 * <p>In practice we always want a Prometheus scrape endpoint at {@code /metrics}. Some Spring Boot
 * test contexts (and lightweight apps) otherwise default to SimpleMeterRegistry and never create a
 * Prometheus registry, which would make {@code /metrics} disappear.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(PrometheusMeterRegistry.class)
@ConditionalOnProperty(prefix = "nanofaas.metrics.prometheus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PrometheusRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean(PrometheusMeterRegistry.class)
    PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    /**
     * Make Prometheus the default registry so framework binders (JVM, HTTP, etc.) are visible in scrape().
     */
    @Bean
    @Primary
    MeterRegistry nanofaasPrimaryMeterRegistry(PrometheusMeterRegistry prometheusMeterRegistry) {
        return prometheusMeterRegistry;
    }
}

