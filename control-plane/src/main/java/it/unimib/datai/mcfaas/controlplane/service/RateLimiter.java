package com.mcfaas.controlplane.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConfigurationProperties(prefix = "mcfaas.rate")
public class RateLimiter {
    private int maxPerSecond = 1000;
    private long windowStartSecond = Instant.now().getEpochSecond();
    private int windowCount = 0;

    /**
     * Thread-safe rate limiting using a sliding window per second.
     * All operations are synchronized to prevent race conditions.
     */
    public synchronized boolean allow() {
        long now = Instant.now().getEpochSecond();
        if (now != windowStartSecond) {
            windowStartSecond = now;
            windowCount = 0;
        }
        windowCount++;
        return windowCount <= maxPerSecond;
    }

    public int getMaxPerSecond() {
        return maxPerSecond;
    }

    public void setMaxPerSecond(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
    }
}
