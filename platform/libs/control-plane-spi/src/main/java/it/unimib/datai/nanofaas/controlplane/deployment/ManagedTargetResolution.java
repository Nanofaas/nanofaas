package it.unimib.datai.nanofaas.controlplane.deployment;

/** Only UNSUPPORTED permits dispatch through the configured external/Service endpoint. */
public record ManagedTargetResolution(boolean supported, ManagedInvocationTarget target) {
    public static final ManagedTargetResolution UNSUPPORTED = new ManagedTargetResolution(false, null);
    public static final ManagedTargetResolution UNAVAILABLE = new ManagedTargetResolution(true, null);

    public ManagedTargetResolution {
        if (!supported && target != null) throw new IllegalArgumentException("Unsupported provider cannot reserve a target");
    }

    public static ManagedTargetResolution reserved(ManagedInvocationTarget target) {
        return new ManagedTargetResolution(true, java.util.Objects.requireNonNull(target));
    }
}
