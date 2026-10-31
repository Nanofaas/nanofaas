package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScalingCooldownTracker {
    private static final long SCALE_UP_COOLDOWN_MS = 30_000;
    private static final long SCALE_DOWN_COOLDOWN_MS = 60_000;

    private final Map<String, Instant> lastScaleUp = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastScaleDown = new ConcurrentHashMap<>();

    private final Map<String, ZeroWindow> zeroWindows = new ConcurrentHashMap<>();

    public boolean allowScaleToZero(FunctionGeneration generation, Instant now) {
        ZeroWindow window = zeroWindows.compute(generation.functionName(), (_, previous) ->
                previous != null && previous.generation().equals(generation)
                        ? previous : new ZeroWindow(generation, now));
        return allow(window.since(), now, SCALE_DOWN_COOLDOWN_MS);
    }

    public void resetScaleToZero(String functionName) {
        zeroWindows.remove(functionName);
    }

    private record ZeroWindow(FunctionGeneration generation, Instant since) { }

    public boolean allowScaleUp(String functionName, Instant now) {
        return allow(lastScaleUp.get(functionName), now, SCALE_UP_COOLDOWN_MS);
    }

    public boolean allowScaleDown(String functionName, Instant now) {
        return allow(lastScaleDown.get(functionName), now, SCALE_DOWN_COOLDOWN_MS);
    }

    public void recordScaleUp(String functionName, Instant now) {
        lastScaleUp.put(functionName, now);
    }

    public void recordScaleDown(String functionName, Instant now) {
        lastScaleDown.put(functionName, now);
    }

    public void clear(String functionName) {
        lastScaleUp.remove(functionName);
        lastScaleDown.remove(functionName);
        resetScaleToZero(functionName);
    }

    private static boolean allow(Instant last, Instant now, long cooldownMs) {
        return last == null || now.toEpochMilli() - last.toEpochMilli() >= cooldownMs;
    }
}
