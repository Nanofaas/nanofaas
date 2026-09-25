package it.unimib.datai.nanofaas.controlplane.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsDefaultsAndOverrides() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RetryProperties properties = context.getBean(RetryProperties.class);
            assertThat(properties.initialBackoff()).isEqualTo(Duration.ofMillis(100));
            assertThat(properties.maxBackoff()).isEqualTo(Duration.ofSeconds(2));
        });
        runner.withPropertyValues("nanofaas.retry.initial-backoff=250ms", "nanofaas.retry.max-backoff=3s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RetryProperties properties = context.getBean(RetryProperties.class);
                    assertThat(properties.initialBackoff()).isEqualTo(Duration.ofMillis(250));
                    assertThat(properties.maxBackoff()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void invalidDurationsAbortStartup() {
        assertStartupFails("nanofaas.retry.initial-backoff=0s");
        assertStartupFails("nanofaas.retry.initial-backoff=-1ns");
        assertStartupFails("nanofaas.retry.initial-backoff=3s");
        assertStartupFails("nanofaas.retry.max-backoff=0s");
        assertStartupFails("nanofaas.retry.max-backoff=-1s");
        assertStartupFails("nanofaas.retry.max-backoff=banana");
    }

    private void assertStartupFails(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RetryProperties.class)
    static class TestConfiguration {
    }
}
