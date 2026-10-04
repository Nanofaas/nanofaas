package it.unimib.datai.nanofaas.modules.offload.oneshot.solver;
import java.time.Duration;
public record SolveLimits(long maxStateLevelProduct, long maxBytes, long deadlineNanos) {
    public SolveLimits {
        if (maxStateLevelProduct < 1 || maxBytes < 1) throw new IllegalArgumentException("positive solver limits required");
    }
    public static SolveLimits forDuration(Duration duration) {
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("positive solver duration required");
        return new SolveLimits(2000000, 64L * 1024 * 1024, System.nanoTime() + duration.toNanos());
    }
}
