package it.unimib.datai.nanofaas.controlplane.deployment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "nanofaas.deployment.wakeup")
public record DeploymentWakeUpProperties(
        Duration timeout,
        Duration pollInterval,
        Duration readyObservationMaxAge
) {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration DEFAULT_READY_OBSERVATION_MAX_AGE = ReplicaStatusSnapshot.DEFAULT_TTL;

    public DeploymentWakeUpProperties {
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        pollInterval = pollInterval == null ? DEFAULT_POLL_INTERVAL : pollInterval;
        readyObservationMaxAge = readyObservationMaxAge == null
                ? DEFAULT_READY_OBSERVATION_MAX_AGE : readyObservationMaxAge;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("nanofaas.deployment.wakeup.timeout must be positive");
        }
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("nanofaas.deployment.wakeup.poll-interval must be positive");
        }
        if (readyObservationMaxAge.isZero() || readyObservationMaxAge.isNegative()) {
            throw new IllegalArgumentException(
                    "nanofaas.deployment.wakeup.ready-observation-max-age must be positive");
        }
    }

    public DeploymentWakeUpProperties(Duration timeout, Duration pollInterval) {
        this(timeout, pollInterval, DEFAULT_READY_OBSERVATION_MAX_AGE);
    }

    public DeploymentWakeUpProperties() {
        this(DEFAULT_TIMEOUT, DEFAULT_POLL_INTERVAL, DEFAULT_READY_OBSERVATION_MAX_AGE);
    }
}
