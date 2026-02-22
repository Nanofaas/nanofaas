package it.unimib.datai.nanofaas.controlplane.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConfigurationProperties(prefix = "nanofaas.rate")
public class RateLimiter {
    private volatile int maxPerSecond = 1000;
    private final AtomicLong windowStartSecond = new AtomicLong(Instant.now().getEpochSecond());
    private final AtomicInteger windowCount = new AtomicInteger();

    /**
     * Thread-safe rate limiting using atomics and a per-second window.
     */
    public boolean allow() {
        long now = Instant.now().getEpochSecond();
        long currentWindow = windowStartSecond.get();
        if (now != currentWindow && windowStartSecond.compareAndSet(currentWindow, now)) {
            windowCount.set(0);
        }
        return windowCount.incrementAndGet() <= maxPerSecond;
    }

    public int getMaxPerSecond() {
        return maxPerSecond;
    }

    public void setMaxPerSecond(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
    }
}
