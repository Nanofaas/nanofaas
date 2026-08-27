package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneRuntimeConfigExtensionTest {
    @Test
    void validatesAndAppliesPositiveRateLimit() {
        RateLimiter limiter = new RateLimiter();
        ControlPlaneRuntimeConfigExtension extension = new ControlPlaneRuntimeConfigExtension(limiter);

        assertThat(extension.validate(Map.of("rateMaxPerSecond", 500))).isEmpty();
        extension.apply(Map.of("rateMaxPerSecond", 500));

        assertThat(limiter.getMaxPerSecond()).isEqualTo(500);
        assertThat(extension.validate(Map.of("rateMaxPerSecond", 0))).isNotEmpty();
    }
}
