package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyControlModuleTest {

    @Test
    void modulePublishesItsAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class,
                Thread.currentThread().getContextClassLoader()).getCandidates())
                .contains(ConcurrencyControlConfiguration.class.getName());
    }

    /**
     * The record has no {@code @ConstructorBinding}: Boot infers constructor binding for a
     * single-constructor record. A regression here would be silent — the defaults would simply
     * apply and the configured values would be ignored.
     */
    @Test
    void propertiesBindFromConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(
                        "nanofaas.concurrency-control.poll-interval-ms=250",
                        "nanofaas.concurrency-control.default-target-in-flight-per-pod=7")
                .run(context -> {
                    ConcurrencyControlProperties props = context.getBean(ConcurrencyControlProperties.class);
                    assertThat(props.pollIntervalMsOrDefault()).isEqualTo(250L);
                    assertThat(props.defaultTargetInFlightPerPodOrDefault()).isEqualTo(7);
                });
    }

    @EnableConfigurationProperties(ConcurrencyControlProperties.class)
    static class PropertiesOnly {
    }
}
