package it.unimib.datai.nanofaas.controlplane.config;

import it.unimib.datai.nanofaas.execution.RetryBackoff;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Startup retry timing configuration. */
@ConfigurationProperties("nanofaas.retry")
public record RetryProperties(Duration initialBackoff, Duration maxBackoff) {
    public RetryProperties {
        initialBackoff = initialBackoff == null ? Duration.ofMillis(100) : initialBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofSeconds(2) : maxBackoff;
        new RetryBackoff(initialBackoff, maxBackoff, () -> 0.0);
    }
}
