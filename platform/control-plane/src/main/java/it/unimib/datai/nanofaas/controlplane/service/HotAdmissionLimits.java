package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.AdmissionLimitsControl;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.ResourceQuota;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;

import java.util.Objects;

/**
 * The core side of {@link AdmissionLimitsControl}: the one place that translates proposed limit
 * values into mutations of the live quotas.
 *
 * <p>It exists so that a runtime-configuration extension can change limits while serving without
 * holding the mutable capacity objects themselves. The quotas validate their own invariants — a
 * positive value, and a per-function share not exceeding its global budget — so an invalid
 * snapshot is rejected by the quota that would have accepted it, not by a second copy of the rule
 * living here.</p>
 */
public final class HotAdmissionLimits implements AdmissionLimitsControl {
    private final RateLimiter rateLimiter;
    private final InvocationCapacity invocationCapacity;
    private final WaiterCapacity waiterCapacity;

    public HotAdmissionLimits(RateLimiter rateLimiter,
                              InvocationCapacity invocationCapacity,
                              WaiterCapacity waiterCapacity) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.invocationCapacity = Objects.requireNonNull(invocationCapacity, "invocationCapacity");
        this.waiterCapacity = Objects.requireNonNull(waiterCapacity, "waiterCapacity");
    }

    @Override
    public Snapshot limits() {
        InvocationCapacity.Limits invocation = invocationCapacity.limits();
        ResourceQuota.Limits waiters = waiterCapacity.limits();
        return new Snapshot(
                rateLimiter.getMaxPerSecond(),
                pair(invocation.executions()),
                pair(invocation.canonicalInputBytes()),
                pair(invocation.physicalInputCopyBytes()),
                pair(waiters));
    }

    @Override
    public void updateLimits(Snapshot limits) {
        Objects.requireNonNull(limits, "limits");
        rateLimiter.setMaxPerSecond(limits.rateMaxPerSecond());
        invocationCapacity.updateLimits(new InvocationCapacity.Limits(
                quota(limits.executions()),
                quota(limits.canonicalInputBytes()),
                quota(limits.physicalInputCopyBytes())));
        waiterCapacity.updateLimits(limits.waiters().global(), limits.waiters().perFunction());
    }

    private static Pair pair(ResourceQuota.Limits limits) {
        return new Pair(limits.global(), limits.perFunction());
    }

    private static ResourceQuota.Limits quota(Pair pair) {
        return new ResourceQuota.Limits(pair.global(), pair.perFunction());
    }
}
