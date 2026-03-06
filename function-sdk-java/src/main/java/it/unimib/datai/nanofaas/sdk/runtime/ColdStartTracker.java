package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ColdStartTracker {

    private final AtomicBoolean firstInvocation = new AtomicBoolean(true);
    private final Instant containerStart = Instant.now();

    public boolean firstInvocation() {
        return firstInvocation.compareAndSet(true, false);
    }

    public long initDurationMs() {
        return Instant.now().toEpochMilli() - containerStart.toEpochMilli();
    }
}
