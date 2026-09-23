package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The mutable Spring binding target for {@code nanofaas.execution-store}.
 *
 * <p>{@link ExecutionStoreProperties} moved into the mandatory {@code :execution-runtime}
 * library (issue #208, Task 9) and became a pure record, so it can no longer carry
 * {@code @ConfigurationProperties} itself — that annotation is Spring, and the runtime library
 * must not depend on Spring. This class is the control plane's binding target instead: Spring
 * populates it from configuration, and {@link #toRuntime()} produces the runtime record,
 * which performs its own default/validation normalization in its compact constructor. No
 * default is duplicated here.
 */
@ConfigurationProperties(prefix = "nanofaas.execution-store")
public class ExecutionStoreBindingProperties {
    private Duration ttl;
    private Duration maxLifetime;
    private Duration syncTtl;
    private long maxOutcomes;
    private long maxKeys;
    private long maxOutcomeBytes;

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public Duration getMaxLifetime() { return maxLifetime; }
    public void setMaxLifetime(Duration maxLifetime) { this.maxLifetime = maxLifetime; }
    public Duration getSyncTtl() { return syncTtl; }
    public void setSyncTtl(Duration syncTtl) { this.syncTtl = syncTtl; }
    public long getMaxOutcomes() { return maxOutcomes; }
    public void setMaxOutcomes(long maxOutcomes) { this.maxOutcomes = maxOutcomes; }
    public long getMaxKeys() { return maxKeys; }
    public void setMaxKeys(long maxKeys) { this.maxKeys = maxKeys; }
    public long getMaxOutcomeBytes() { return maxOutcomeBytes; }
    public void setMaxOutcomeBytes(long maxOutcomeBytes) { this.maxOutcomeBytes = maxOutcomeBytes; }

    /** Builds the runtime record; every default and validation lives in its compact constructor. */
    public ExecutionStoreProperties toRuntime() {
        return new ExecutionStoreProperties(ttl, maxLifetime, syncTtl, maxOutcomes, maxKeys, maxOutcomeBytes);
    }
}
