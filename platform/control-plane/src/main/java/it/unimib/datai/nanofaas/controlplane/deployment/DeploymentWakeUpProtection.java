package it.unimib.datai.nanofaas.controlplane.deployment;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.Objects;

/** Prevents a concurrent scaler from undoing a zero-to-one deployment wake-up. */
public final class DeploymentWakeUpProtection extends ApplicationEvent {
    private final String functionName;
    private final Instant expiresAt;

    public DeploymentWakeUpProtection(String functionName, Instant expiresAt) {
        super(functionName);
        this.functionName = requireText(functionName, "functionName");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public String functionName() {
        return functionName;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
