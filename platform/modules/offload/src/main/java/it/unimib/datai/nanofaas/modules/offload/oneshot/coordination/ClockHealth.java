package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import java.time.*;
import java.util.function.Supplier;
/** Clock health is supplied by an external measurement, never inferred from message RTT. */
public final class ClockHealth {
    private record Sample(Duration offset,Instant at) {}
    private final Duration threshold,maxAge; private final Supplier<Instant> now;
    private volatile Sample sample;
    public ClockHealth(Duration threshold,Duration maxAge,Supplier<Instant> now) {
        if(threshold.isNegative() || maxAge.isNegative() || maxAge.isZero()) throw new IllegalArgumentException("invalid clock bounds");
        this.threshold=threshold; this.maxAge=maxAge; this.now=now;
    }
    public synchronized void sample(Duration offset,Instant measuredAt) {
        java.util.Objects.requireNonNull(offset);java.util.Objects.requireNonNull(measuredAt);
        if(measuredAt.isAfter(now.get()) || (sample!=null && measuredAt.isBefore(sample.at()))) throw new IllegalArgumentException("clock measurement must advance without future dating");
        sample=new Sample(offset,measuredAt);
    }
    public boolean healthy() {
        var reading=sample; var time=now.get();
        return reading!=null && reading.offset().compareTo(threshold.negated())>=0 && reading.offset().compareTo(threshold)<=0
            && !reading.at().isAfter(time) && Duration.between(reading.at(),time).compareTo(maxAge)<=0;
    }
}
